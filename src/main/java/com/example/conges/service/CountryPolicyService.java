package com.example.conges.service;

import com.example.conges.entity.CountryLeavePolicy;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.CountryLeavePolicyRepository;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plafonds, prorata et règles <strong>par pays métier</strong> (TN, MA, FR, etc.) pour les congés.
 * Ces règles sont exclusivement dans cette application ; elles ne sont pas « calculées » par Dolibarr.
 */
@Service
@RequiredArgsConstructor
public class CountryPolicyService {

    /** Autorisations courtes hors France : durée fixe et plafond mensuel (acceptées + en attente à la création). */
    public static final int NON_FR_SHORT_LEAVE_MINUTES = 120;
    public static final int NON_FR_SHORT_LEAVE_MONTHLY_CAP = 2;

    private static final Map<TypeConge, Integer> DEFAULT_POLICY = new EnumMap<>(TypeConge.class);
    private static final Map<String, Double> DEFAULT_MONTHLY_PAYE = Map.of("TN", 1.83, "MA", 1.05, "FR", 2.08);
    /** Outre-mer rattaché France pour règles RH. */
    private static final Set<String> FR_OVERSEAS = Set.of(
            "GP", "MQ", "GF", "RE", "YT", "PM", "BL", "MF", "WF", "PF", "NC", "TF"
    );

    static {
        DEFAULT_POLICY.put(TypeConge.PAYE, 25);
        /* Valeur par défaut si la politique RH (rtt_annual_days) n’est pas renseignée ; à ajuster par pays / accord. */
        DEFAULT_POLICY.put(TypeConge.COURTE_DUREE, 10);
        /* Référence ; TN/MA : 7 j/an maladie par défaut si la BD n’a pas un quota RH > 0. */
        DEFAULT_POLICY.put(TypeConge.MALADIE, 7);
        DEFAULT_POLICY.put(TypeConge.SANS_SOLDE, 0);
    }

    private final CountryLeavePolicyRepository countryLeavePolicyRepository;

    public String normalizeBusinessCountry(String pays) {
        if (pays == null || pays.isBlank()) {
            return "TN";
        }
        // Supprime les accents (ex. « Français » → « FRANCAIS ») avant la recherche par mots-clés,
        // sinon le « Ç » casse le contains("FRANC"). Synchronisé avec le frontend country.js.
        String c = Normalizer.normalize(pays.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT);
        if (c.isEmpty()) {
            return "TN";
        }
        
        // Outre-mer français → FR
        if (FR_OVERSEAS.contains(c)) {
            return "FR";
        }
        
        // Codes ISO2 valides uniquement
        if ("TN".equals(c) || "FR".equals(c) || "MA".equals(c)) {
            return c;
        }
        
        // Recherche par mots-clés (ordre : TN, FR, MA)
        if (c.contains("TUNIS")) {
            return "TN";
        }
        if (c.contains("FRANCE") || c.contains("FRANC")) {
            return "FR";
        }
        if (c.contains("MAROC") || c.contains("MOROCCO")) {
            return "MA";
        }
        
        // Défaut : Tunisie
        return "TN";
    }

    public Optional<CountryLeavePolicy> findPolicy(String countryCode, TypeConge typeConge) {
        return countryLeavePolicyRepository.findFirstByCountryCodeAndTypeConge(
                normalizeBusinessCountry(countryCode), typeConge);
    }

    /**
     * Quota annuel par type pour le pays métier.
     * Tunisie & Maroc : congé maladie = 7 j/an par défaut si la politique RH n’a pas un quota strictement positif.
     */
    public int getAnnualQuota(String countryCode, TypeConge typeConge) {
        String normalized = normalizeBusinessCountry(countryCode);
        if (typeConge == TypeConge.COURTE_DUREE && !"FR".equals(normalized)) {
            return 0;
        }
        if (typeConge == TypeConge.MALADIE && "FR".equals(normalized)) {
            return findPolicy(normalized, typeConge)
                    .map(CountryLeavePolicy::getAnnualQuota)
                    .filter(q -> q != null && q >= 0)
                    .orElse(7);
        }
        if (typeConge == TypeConge.MALADIE && ("TN".equals(normalized) || "MA".equals(normalized))) {
            Integer configured = findPolicy(normalized, typeConge)
                    .map(CountryLeavePolicy::getAnnualQuota)
                    .orElse(null);
            if (configured != null && configured > 0) {
                return configured;
            }
            return 7;
        }
        return findPolicy(normalized, typeConge)
                .map(CountryLeavePolicy::getAnnualQuota)
                .orElseGet(() -> DEFAULT_POLICY.getOrDefault(typeConge, 0));
    }

    public boolean isRttEnabledForCountry(String pays) {
        String c = normalizeBusinessCountry(pays);
        if (!"FR".equals(c)) {
            return false;
        }
        return findPolicy(c, TypeConge.COURTE_DUREE)
                .map(CountryLeavePolicy::isRttEnabled)
                .orElse(true);
    }

    /** Jours sortie courte durée / an (France uniquement ; champs configurables conservent le préfixe rtt_*). */
    public int getAnnualRttQuota(String pays) {
        String c = normalizeBusinessCountry(pays);
        if (!"FR".equals(c) || !isRttEnabledForCountry(pays)) {
            return 0;
        }
        return findPolicy(c, TypeConge.COURTE_DUREE)
                .map(p -> {
                    if (p.getRttAnnualDays() != null) {
                        return p.getRttAnnualDays();
                    }
                    return Optional.ofNullable(p.getAnnualQuota()).orElse(DEFAULT_POLICY.get(TypeConge.COURTE_DUREE));
                })
                .orElse(DEFAULT_POLICY.get(TypeConge.COURTE_DUREE));
    }

    public double getMonthlyPaidLeaveRate(String pays) {
        String c = normalizeBusinessCountry(pays);
        return findPolicy(c, TypeConge.PAYE)
                .map(CountryLeavePolicy::getMonthlyAccrualRate)
                .filter(rate -> rate != null && rate > 0)
                .orElse(DEFAULT_MONTHLY_PAYE.getOrDefault(c, DEFAULT_MONTHLY_PAYE.get("TN")));
    }

    /**
     * Nombre de mois d’acquisition CP sur l’année civile {@code year} (min 0, max 12),
     * du mois d’embauche jusqu’à décembre si embauche dans l’année, sinon 12 mois complets.
     */
    public int countPaidLeaveAccrualMonths(UserEntity user, int year) {
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        LocalDate hire = user.getHireDate();
        if (hire == null && user.getCreatedAt() != null) {
            hire = user.getCreatedAt().toLocalDate();
        }
        if (hire == null) {
            return 12;
        }
        if (hire.isAfter(yearEnd)) {
            return 0;
        }
        LocalDate effectiveStart = hire.isBefore(yearStart) ? yearStart : hire;
        if (effectiveStart.isAfter(yearEnd)) {
            return 0;
        }
        LocalDate cur = effectiveStart.withDayOfMonth(1);
        LocalDate endMonth = yearEnd.withDayOfMonth(1);
        int months = 0;
        while (!cur.isAfter(endMonth)) {
            months++;
            cur = cur.plusMonths(1);
        }
        return Math.min(12, Math.max(0, months));
    }

    /** Plafond CP (jours) pour l’année civile : mois d’acquisition × taux mensuel pays, arrondi. */
    public int getPaidLeaveEntitlementCeilingForYear(UserEntity user, int year) {
        int months = countPaidLeaveAccrualMonths(user, year);
        double monthly = getMonthlyPaidLeaveRate(user.getPays());
        return Math.max(0, (int) Math.round(months * monthly));
    }

    @Transactional(readOnly = true)
    public List<CountryLeavePolicy> getAllPolicies() {
        return countryLeavePolicyRepository.findAll();
    }

    @Transactional
    public CountryLeavePolicy upsertPolicy(
            String countryCode,
            TypeConge typeConge,
            Integer annualQuota,
            Double monthlyAccrualRate,
            Boolean rttEnabled,
            Integer rttAnnualDays
    ) {
        String normalized = normalizeBusinessCountry(countryCode);
        CountryLeavePolicy policy = countryLeavePolicyRepository
                .findFirstByCountryCodeAndTypeConge(normalized, typeConge)
                .orElseGet(() -> CountryLeavePolicy.builder()
                        .countryCode(normalized)
                        .typeConge(typeConge)
                        .build());
        policy.setAnnualQuota(annualQuota != null ? annualQuota : 0);
        policy.setMonthlyAccrualRate(monthlyAccrualRate);
        if (rttEnabled != null) {
            policy.setRttEnabled(rttEnabled);
        }
        policy.setRttAnnualDays(rttAnnualDays);
        return countryLeavePolicyRepository.save(policy);
    }

    /**
     * Compat API existante (sans champs optionnels).
     */
    @Transactional
    public CountryLeavePolicy upsertPolicy(String countryCode, TypeConge typeConge, Integer annualQuota) {
        return upsertPolicy(countryCode, typeConge, annualQuota, null, null, null);
    }
}
