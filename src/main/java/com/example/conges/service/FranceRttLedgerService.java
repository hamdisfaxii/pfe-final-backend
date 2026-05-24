package com.example.conges.service;

import com.example.conges.config.FranceRttProperties;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.EmployeeFranceRttBalance;
import com.example.conges.entity.FranceRttAccrualMode;
import com.example.conges.entity.FranceRttSettings;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.EmployeeFranceRttBalanceRepository;
import com.example.conges.repository.FranceRttSettingsRepository;
import com.example.conges.repository.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FranceRttLedgerService {

    private static final int SCALE = 2;
    private static final Long SETTINGS_ID = 1L;

    private final EmployeeFranceRttBalanceRepository balanceRepository;
    private final FranceRttSettingsRepository settingsRepository;
    private final DemandeCongeRepository demandeCongeRepository;
    private final UserRepository userRepository;
    private final CountryPolicyService countryPolicyService;
    private final FranceRttComputationService computation;
    private final FranceRttProperties properties;

    public boolean isLocalLedgerActive() {
        return properties.isLocalLedgerEnabled();
    }

    public boolean appliesFranceLegalRttScope(UserEntity user) {
        if (!"FR".equals(countryPolicyService.normalizeBusinessCountry(user.getPays()))) {
            return false;
        }
        if (!countryPolicyService.isRttEnabledForCountry(user.getPays())) {
            return false;
        }
        if (!user.isContractActive()) {
            return false;
        }
        BigDecimal wh = FranceRttComputationService.resolvedWeeklyHours(user);
        return wh.doubleValue() > 35.0 + 1e-9;
    }

    public boolean isFranceRttTrackedForUi(UserEntity user) {
        return "FR".equals(countryPolicyService.normalizeBusinessCountry(user.getPays()))
                && countryPolicyService.isRttEnabledForCountry(user.getPays());
    }

    /** Persistance lignes ledger (droits acquisition > 35 h). */
    public boolean usesPersistedFranceRttLedger(UserEntity user) {
        return properties.isLocalLedgerEnabled()
                && isFranceRttTrackedForUi(user)
                && appliesFranceLegalRttScope(user);
    }

    public boolean shouldSkipDolibarrConsumption(DemandeConge demande) {
        UserEntity user = demande.getUser();
        return properties.isSkipDolibarrRttConsume()
                && demande.getTypeConge() == TypeConge.COURTE_DUREE
                && usesPersistedFranceRttLedger(user);
    }

    private FranceRttSettings defaultSettingsFallback() {
        return FranceRttSettings.builder()
                .id(SETTINGS_ID)
                .accrualMode(FranceRttAccrualMode.CONTRACT_HOURS)
                .adminOverrideDays(null)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Transactional(readOnly = true)
    public FranceRttSettings resolvedSettingsSnapshot() {
        return settingsRepository.findById(SETTINGS_ID).orElseGet(this::defaultSettingsFallback);
    }

    @Transactional
    public void upsertGlobalSettings(FranceRttAccrualMode mode, Integer adminOverrideDays) {
        FranceRttSettings s = settingsRepository
                .findById(SETTINGS_ID)
                .orElseGet(() -> FranceRttSettings.builder().id(SETTINGS_ID).build());
        s.setAccrualMode(mode);
        s.setAdminOverrideDays(adminOverrideDays);
        s.setUpdatedAt(LocalDateTime.now());
        settingsRepository.save(s);
    }

    /**
     * Projection solde décimale en lecture seule (aucune écriture : safe pour GET {@code /conge/solde}).
     * La persistance lignes ledger se fait après validation congés, cron RH ou PATCH contrat.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> ledgerSnapshot(UserEntity user, int year, LocalDate asOf) {
        Map<String, Object> m = new HashMap<>();
        if (!properties.isLocalLedgerEnabled() || !isFranceRttTrackedForUi(user)) {
            return m;
        }
        FranceRttSettings settings = resolvedSettingsSnapshot();
        FranceRttAccrualMode mode = Objects.requireNonNullElse(
                settings.getAccrualMode(), FranceRttAccrualMode.CONTRACT_HOURS);
        LocalDateTime lastTs = balanceRepository
                .findByUserAndCalendarYear(user, year)
                .map(EmployeeFranceRttBalance::getLastRttUpdate)
                .filter(Objects::nonNull)
                .orElse(LocalDateTime.now());

        BigDecimal fullAnnual = contractualFullAnnualBeforeProrata(user, settings);
        BigDecimal rowTotal = computation.proratedAnnualTotal(fullAnnual, user, year);
        BigDecimal usedApproved = syncUsedFromApprovedDemandes(user.getId(), year);
        BigDecimal pending = pendingCourteAsBigDecimal(year, user.getId());
        BigDecimal earned = computation.earnedCapYtd(mode, rowTotal, user, year, asOf);
        BigDecimal remaining =
                FranceRttComputationService.maxZero(earned.subtract(usedApproved).subtract(pending));

        if (usedApproved.compareTo(earned) > 0) {
            log.warn(
                    "Employé {} : usages RTT approuvés ({}) au-delà du plafond projeté ({}) ; les nouvelles demandes restent bloquées.",
                    user.getId(),
                    usedApproved,
                    earned);
        }

        m.put("rtt_total", scale(rowTotal));
        m.put("rtt_used", scale(usedApproved));
        m.put("rtt_pending", scale(pending));
        m.put("rtt_remaining", scale(remaining));
        m.put("rtt_accrual_mode", mode.name());
        m.put("last_rtt_update", lastTs);
        return m;
    }

    @Transactional(readOnly = true)
    protected BigDecimal pendingCourteAsBigDecimal(int bookingYear, Long userId) {
        BigDecimal pend = Objects.requireNonNullElse(
                demandeCongeRepository.sumJoursCourteDurePourAnneeEtStatut(
                        userId, StatutConge.EN_ATTENTE.name(), bookingYear),
                BigDecimal.ZERO);
        return pend.setScale(SCALE, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    protected BigDecimal syncUsedFromApprovedDemandes(Long userId, int year) {
        BigDecimal used = Objects.requireNonNullElse(
                demandeCongeRepository.sumJoursCourteDurePourAnneeEtStatut(
                        userId, StatutConge.ACCEPTE.name(), year),
                BigDecimal.ZERO);
        return used.setScale(SCALE, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    protected BigDecimal contractualFullAnnualBeforeProrata(UserEntity user, FranceRttSettings settings) {
        if (settings.getAdminOverrideDays() != null && settings.getAdminOverrideDays() >= 0) {
            return BigDecimal.valueOf(settings.getAdminOverrideDays())
                    .setScale(SCALE, RoundingMode.HALF_UP);
        }
        return computation.fullYearEntitlementFromWeeklyHours(FranceRttComputationService.resolvedWeeklyHours(user));
    }

    /**
     * Moteur français : remplace vérif quota pays / Dolibarr pour les courte durée France lorsque activé en config.
     */
    public boolean governsFranceCourteRequests(UserEntity user) {
        return properties.isLocalLedgerEnabled()
                && isFranceRttTrackedForUi(user);
    }

    @Transactional(readOnly = true)
    public void assertSufficientFranceCourte(UserEntity user, int year, LocalDate asOf, int joursDemandes) {
        assertSufficientFranceCourteExact(user, year, asOf, (double) joursDemandes);
    }

    @Transactional(readOnly = true)
    public void assertSufficientFranceCourteExact(UserEntity user, int year, LocalDate asOf, double joursDemandesExact) {
        if (!governsFranceCourteRequests(user)) {
            return;
        }
        if (!(joursDemandesExact > 0d)) {
            throw new IllegalArgumentException("Le nombre de jours RTT doit être positif.");
        }
        FranceRttSettings settings = resolvedSettingsSnapshot();
        FranceRttAccrualMode mode = Objects.requireNonNullElse(
                settings.getAccrualMode(), FranceRttAccrualMode.CONTRACT_HOURS);
        BigDecimal fullAnnual = contractualFullAnnualBeforeProrata(user, settings);
        BigDecimal rowTotal = computation.proratedAnnualTotal(fullAnnual, user, year);
        BigDecimal earned =
                computation.earnedCapYtd(mode, rowTotal, user, year, asOf);
        BigDecimal usedApproved = syncUsedFromApprovedDemandes(user.getId(), year);
        BigDecimal pending = pendingCourteAsBigDecimal(year, user.getId());
        BigDecimal dispo =
                earned.subtract(usedApproved).subtract(pending).setScale(SCALE, RoundingMode.HALF_UP);
        if (BigDecimal.valueOf(joursDemandesExact).compareTo(dispo) > 0) {
            throw new IllegalStateException(String.format(
                    "Solde RTT France insuffisant : %.2f jour(s) demandé(s), %s jour(s) disponibles au regard du moteur (incluant demandes en attente).",
                    joursDemandesExact, dispo.stripTrailingZeros().toPlainString()));
        }
    }

    /**
     * Recalcule ligne stockée depuis les temps approuvés (source de vérité : demandes {@link StatutConge#ACCEPTE}).
     */
    @Transactional
    public void consumeFranceRttLedgerOnApprovedLeave(DemandeConge saved) {
        if (!shouldSkipDolibarrConsumption(saved)) {
            return;
        }
        int year = saved.getDateDebut().getYear();
        UserEntity ref = Objects.requireNonNull(saved.getUser());
        refreshPersistedTotals(ref.getId(), year, LocalDate.now());
    }

    @Transactional
    public EmployeeFranceRttBalance refreshPersistedTotals(Long userId, int year, LocalDate asOf) {
        UserEntity user = Objects.requireNonNull(
                userRepository.findById(userId).orElse(null), "Employé inconnu pour solde RTT");
        return ensurePersistedTotals(user, year, asOf);
    }

    @Transactional
    public EmployeeFranceRttBalance ensurePersistedTotals(UserEntity user, int year, LocalDate asOf) {
        if (!usesPersistedFranceRttLedger(user)) {
            throw new IllegalStateException("Persistance ledger RTT réservée au droit légal (>35 h / FR).");
        }
        EmployeeFranceRttBalance bal =
                balanceRepository.findByUserAndCalendarYear(user, year).orElse(null);
        FranceRttSettings settings = resolvedSettingsSnapshot();
        FranceRttAccrualMode mode = Objects.requireNonNullElse(
                settings.getAccrualMode(), FranceRttAccrualMode.CONTRACT_HOURS);
        BigDecimal fullAnnual = contractualFullAnnualBeforeProrata(user, settings);
        BigDecimal rowTotal = computation.proratedAnnualTotal(fullAnnual, user, year);
        BigDecimal syncedUsed = syncUsedFromApprovedDemandes(user.getId(), year);
        BigDecimal earned = computation.earnedCapYtd(mode, rowTotal, user, year, asOf);
        BigDecimal pending = pendingCourteAsBigDecimal(year, user.getId());
        LocalDateTime now = LocalDateTime.now();

        if (syncedUsed.compareTo(earned) > 0) {
            log.error(
                    "Soldes RTT incohérents user={} année={} : {} j utilisés déclarés > {} max acquis.",
                    user.getId(),
                    year,
                    syncedUsed.stripTrailingZeros().toPlainString(),
                    earned.stripTrailingZeros().toPlainString());
        }

        if (bal == null) {
            bal = EmployeeFranceRttBalance.builder()
                    .user(user)
                    .calendarYear(year)
                    .rttTotal(scale(rowTotal))
                    .rttUsed(scale(syncedUsed))
                    .rttRemaining(FranceRttComputationService.maxZero(
                            earned.subtract(syncedUsed).subtract(pending)))
                    .lastRttUpdate(now)
                    .build();
            return balanceRepository.save(bal);
        }

        bal.setRttTotal(scale(rowTotal));
        bal.setRttUsed(scale(syncedUsed));
        bal.setRttRemaining(
                FranceRttComputationService.maxZero(earned.subtract(syncedUsed).subtract(pending)));
        bal.setLastRttUpdate(now);
        return balanceRepository.save(bal);
    }

    @Transactional(readOnly = true)
    public EmployeeFranceRttBalance getPersistedBalance(UserEntity user, int year) {
        return balanceRepository.findByUserAndCalendarYear(user, year).orElse(null);
    }

    @Transactional
    public void setRttRemainingOverride(UserEntity user, int year, double newRemaining) {
        LocalDateTime now = LocalDateTime.now();
        EmployeeFranceRttBalance bal = balanceRepository
                .findByUserAndCalendarYear(user, year)
                .orElse(null);
        if (bal == null) {
            bal = EmployeeFranceRttBalance.builder()
                    .user(user)
                    .calendarYear(year)
                    .rttTotal(BigDecimal.ZERO)
                    .rttUsed(BigDecimal.ZERO)
                    .rttRemaining(BigDecimal.ZERO)
                    .lastRttUpdate(now)
                    .build();
        }
        bal.setRttRemaining(BigDecimal.valueOf(newRemaining).max(BigDecimal.ZERO));
        bal.setLastRttUpdate(now);
        balanceRepository.save(bal);
    }

    /** Tâche 1er janvier : resynchroniser tous les compteurs projetés depuis les décisions métier stockées en base. */
    @Transactional
    public void syncAllEligibleFranceBalancesForYear(int calendarYear, LocalDate asOf) {
        if (!properties.isLocalLedgerEnabled()) {
            return;
        }
        for (UserEntity u : userRepository.findAll()) {
            try {
                if (usesPersistedFranceRttLedger(u)) {
                    ensurePersistedTotals(u, calendarYear, asOf);
                }
            } catch (Exception ex) {
                log.warn("RTT rollover ignoré pour user {} : {}", u.getId(), ex.getMessage());
            }
        }
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
