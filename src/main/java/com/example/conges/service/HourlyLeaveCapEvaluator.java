package com.example.conges.service;

import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import com.example.conges.repository.DemandeCongeRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Limite métier hors France : autorisations « 2h » avec {@link CountryPolicyService#NON_FR_SHORT_LEAVE_MINUTES}.
 */
@Component
@RequiredArgsConstructor
public class HourlyLeaveCapEvaluator {

    static final String LIMITE_MENSUELLE_MSG =
            "Limite mensuelle de 2 autorisations courtes atteinte.";

    private final CountryPolicyService countryPolicyService;
    private final DemandeCongeRepository demandeCongeRepository;

    public boolean isNonFranceHourlyShortLeave(DemandeConge d) {
        if (d == null || d.getTypeConge() != TypeConge.COURTE_DUREE) {
            return false;
        }
        Integer dm = d.getDureePermissionMinutes();
        if (dm == null || dm <= 0) {
            return false;
        }
        String pays = d.getUser() != null ? d.getUser().getPays() : null;
        return !"FR".equals(countryPolicyService.normalizeBusinessCountry(pays));
    }

    /** À l’acceptation définitive : refuse si déjà 2 autres acceptées le même mois. */
    public void assertMonthlyCapOnAccept(DemandeConge demande, Long excludeDemandeId) {
        if (!isNonFranceHourlyShortLeave(demande)) {
            return;
        }
        LocalDate jour = demande.getDateDebut();
        if (jour == null) {
            throw new IllegalStateException(LIMITE_MENSUELLE_MSG);
        }
        LocalDate start = jour.withDayOfMonth(1);
        LocalDate end = jour.withDayOfMonth(jour.lengthOfMonth());
        long autres = demandeCongeRepository.countAcceptedNonFranceHourlyShortLeavesInMonth(
                demande.getUser().getId(), StatutConge.ACCEPTE, start, end, excludeDemandeId);
        if (autres >= CountryPolicyService.NON_FR_SHORT_LEAVE_MONTHLY_CAP) {
            throw new IllegalStateException(LIMITE_MENSUELLE_MSG);
        }
    }

    /** À la création : blocage si déjà ≥ plafond (acceptées ou en attente). */
    public void assertMonthlyCapForNewRequest(Long userId, LocalDate jourDemande, String paysUser) {
        if ("FR".equals(countryPolicyService.normalizeBusinessCountry(paysUser))) {
            return;
        }
        LocalDate start = jourDemande.withDayOfMonth(1);
        LocalDate end = jourDemande.withDayOfMonth(jourDemande.lengthOfMonth());
        long n = demandeCongeRepository.countShortHourlyLeavesInMonthForStatuses(
                userId,
                List.of(StatutConge.ACCEPTE, StatutConge.EN_ATTENTE),
                start,
                end);
        if (n >= CountryPolicyService.NON_FR_SHORT_LEAVE_MONTHLY_CAP) {
            throw new IllegalArgumentException(LIMITE_MENSUELLE_MSG);
        }
    }
}
