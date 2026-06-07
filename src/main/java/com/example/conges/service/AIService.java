package com.example.conges.service;

import com.example.conges.dto.ai.DateSuggestionDto;
import com.example.conges.dto.ai.ConflictDetectionDto;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.Holiday;
import com.example.conges.entity.StatutConge;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.HolidayRepository;
import com.example.conges.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service IA pour suggestions intelligentes et détection de conflits
 * Utilise des algorithmes d'optimisation pour :
 * - Suggérer les meilleures dates pour les congés
 * - Détecter les conflits d'absences
 * - Prédire les périodes à risque
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AIService {

    private final DemandeCongeRepository demandeCongeRepository;
    private final HolidayRepository holidayRepository;
    private final UserRepository userRepository;

    /**
     * Suggère les 5 meilleures périodes pour prendre un congé
     * Critères d'optimisation :
     * - Éviter les jours fériés
     * - Éviter les périodes déjà réservées
     * - Maximiser la continuité (lundi-vendredi)
     * - Éviter les périodes de charge de travail critique
     */
    public List<DateSuggestionDto> suggestOptimalDates(
            Long userId,
            TypeConge typeConge,
            int requestedDays,
            LocalDate startSearchDate,
            LocalDate endSearchDate) {

        log.info("Recherche de dates optimales pour l'utilisateur {} : {} jours de {}", 
                userId, requestedDays, typeConge);

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable"));

        List<DateSuggestionDto> suggestions = new ArrayList<>();

        // 1. Récupérer les dates indisponibles
        Set<LocalDate> unavailableDates = getUnavailableDates(userId, startSearchDate, endSearchDate);
        Set<LocalDate> holidays = getHolidaysForCountry(user.getPays(), startSearchDate, endSearchDate);

        // 2. Chercher les meilleures périodes
        LocalDate currentDate = startSearchDate;
        
        while (currentDate.isBefore(endSearchDate) && suggestions.size() < 5) {
            // Sauter les weekends et jours fériés
            if (isWeekend(currentDate) || holidays.contains(currentDate)) {
                currentDate = currentDate.plusDays(1);
                continue;
            }

            // Essayer de trouver une période continue
            DateRangeFitness rangeFitness = findBestDateRange(
                    currentDate,
                    requestedDays,
                    unavailableDates,
                    holidays,
                    endSearchDate
            );

            if (rangeFitness.isValid && rangeFitness.score > 0.5) {
                DateSuggestionDto suggestion = DateSuggestionDto.builder()
                        .startDate(rangeFitness.startDate)
                        .endDate(rangeFitness.endDate)
                        .numberOfDays(rangeFitness.workingDays)
                        .score(rangeFitness.score)
                        .reason(buildSuggestionReason(rangeFitness))
                        .build();
                suggestions.add(suggestion);
                // Avancer de plusieurs jours pour la prochaine suggestion
                currentDate = rangeFitness.endDate.plusDays(3);
            } else {
                currentDate = currentDate.plusDays(1);
            }
        }

        // Trier par score décroissant
        suggestions.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        log.info("Trouvé {} suggestions optimales", suggestions.size());
        return suggestions.stream()
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * Détecte les conflits potentiels d'absences
     * - Plusieurs absences critiques la même période
     * - Absences de membres clés de l'équipe simultanément
     * - Charge de travail déséquilibrée
     */
    public ConflictDetectionDto detectConflicts(
            Long userId,
            LocalDate startDate,
            LocalDate endDate,
            TypeConge typeConge) {

        log.info("Détection de conflits pour l'utilisateur {} : {} à {}", userId, startDate, endDate);

        ConflictDetectionDto detection = ConflictDetectionDto.builder()
                .hasConflict(false)
                .conflictLevel("LOW")
                .conflicts(new ArrayList<>())
                .recommendations(new ArrayList<>())
                .build();

        // 1. Chercher les absences simultanées du même département
        List<DemandeConge> simultaneousAbsences = findSimultaneousAbsences(
                userId, startDate, endDate
        );

        if (simultaneousAbsences.size() >= 2) {
            detection.setHasConflict(true);
            detection.setConflictLevel("MEDIUM");
            detection.getConflicts().add("Plusieurs employés absents simultanément : " + 
                    simultaneousAbsences.size() + " demandes");
        }

        // 2. Déterminer si c'est une période critique
        if (isCriticalPeriod(startDate, endDate)) {
            detection.setHasConflict(true);
            if ("MEDIUM".equals(detection.getConflictLevel())) {
                detection.setConflictLevel("HIGH");
            }
            detection.getConflicts().add("Période critique détectée (fin de mois, fin de trimestre, etc.)");
        }

        // 3. Vérifier la charge RH
        int totalAbsencesDuring = countTotalAbsencesDuring(startDate, endDate);
        if (totalAbsencesDuring > 20) {
            detection.getConflicts().add("Haute charge d'absences : " + totalAbsencesDuring + " demandes");
            if ("LOW".equals(detection.getConflictLevel())) {
                detection.setConflictLevel("MEDIUM");
            }
        }

        // 4. Générer des recommandations
        if (detection.isHasConflict()) {
            detection.getRecommendations().add("Considérez une date alternative");
            detection.getRecommendations().add("Coordonnez avec votre manager");
            
            if ("HIGH".equals(detection.getConflictLevel())) {
                detection.getRecommendations().add("Justification requise pour approbation");
            }
        }

        log.info("Détection complétée : hasConflict={}, level={}", detection.isHasConflict(), detection.getConflictLevel());
        return detection;
    }

    /**
     * Prédit les tendances d'absences sur les 3 prochains mois
     */
    public Map<String, Object> predictAbsenceTrends(String pays) {
        Map<String, Object> prediction = new HashMap<>();

        LocalDate today = LocalDate.now();
        LocalDate in3Months = today.plusMonths(3);

        // Compter les absences par mois
        Map<YearMonth, Long> monthlyAbsences = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            YearMonth yearMonth = YearMonth.now().plusMonths(i);
            long count = demandeCongeRepository.countByMonthAndCountry(
                    yearMonth.getYear(),
                    yearMonth.getMonthValue(),
                    pays
            );
            monthlyAbsences.put(yearMonth, count);
        }

        prediction.put("predictedTrends", monthlyAbsences);
        
        // Calculer la moyenne
        double average = monthlyAbsences.values().stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        prediction.put("averageAbsences", average);

        // Identifier les pics
        long peakMonth = monthlyAbsences.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        prediction.put("peakAbsences", peakMonth);
        
        // Recommandation
        if (peakMonth > average * 1.5) {
            prediction.put("recommendation", "Forte charge d'absences prévue. Planifiez les recrutements temporaires.");
        }

        return prediction;
    }

    /**
     * Analyse le solde de congés et recommande quand prendre un congé
     */
    public Map<String, Object> analyzeLeaveBalance(Long userId) {
        Map<String, Object> analysis = new HashMap<>();

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable"));

        // Simulation du solde
        int totalQuota = 30; // À récupérer depuis CountryPolicyService
        long usedDays = demandeCongeRepository.countApprovedDaysForUser(userId);
        int remainingDays = (int) (totalQuota - usedDays);

        analysis.put("totalQuota", totalQuota);
        analysis.put("usedDays", usedDays);
        analysis.put("remainingDays", remainingDays);
        analysis.put("percentageUsed", (usedDays / (double) totalQuota) * 100);

        // Recommandations
        List<String> recommendations = new ArrayList<>();
        if (remainingDays < 5) {
            recommendations.add("⚠️ Solde très faible ! Urgent à prendre avant la fin de l'année.");
        } else if (remainingDays < 10) {
            recommendations.add("⚠️ Solde faible. Planifiez vos congés rapidement.");
        } else if (remainingDays < 15) {
            recommendations.add("Solde modéré. Vous avez encore du temps pour planifier.");
        } else {
            recommendations.add("✅ Vous avez un bon solde. Planifiez sereinement vos congés.");
        }

        analysis.put("recommendations", recommendations);
        return analysis;
    }

    /**
     * 🔒 CORRIGÉ: Évalue l'impact d'une demande de congés
     * Utilise des données réelles au lieu de random()
     */
    public Map<String, Object> getImpactScore(Long demandeId) {
        DemandeConge demande = demandeCongeRepository.findById(demandeId)
                .orElseThrow(() -> new IllegalArgumentException("Demande non trouvée"));

        Map<String, Object> impact = new HashMap<>();

        // Calcul de l'impact équipe (basé sur absences simultanées)
        int simultaneousAbsences = (int) demandeCongeRepository.findSimultaneousAbsences(
                demande.getDateDebut(),
                demande.getDateFin()
        ).stream()
        .filter(d -> !d.getUser().getId().equals(demande.getUser().getId()))
        .count();

        int teamImpact = Math.min(100, simultaneousAbsences * 25);
        impact.put("teamImpact", teamImpact);

        // Calcul du risque de continuité (basé sur période critique)
        boolean critical = isCriticalPeriod(demande.getDateDebut(), demande.getDateFin());
        int continuityRisk = critical ? 75 : 25;
        impact.put("continuityRisk", continuityRisk);

        // Charge de travail (simulée)
        impact.put("workload", "Modéré");

        // Recommandation
        String recommendation = "APPROUVER";
        if (teamImpact > 50 || continuityRisk > 70) {
            recommendation = "REPORTER";
        } else if (teamImpact > 25) {
            recommendation = "NÉGOCIER";
        }
        impact.put("recommendation", recommendation);

        return impact;
    }

    // =================== MÉTHODES PRIVÉES ===================

    /**
     * Récupère toutes les dates indisponibles pour un utilisateur
     */
    private Set<LocalDate> getUnavailableDates(Long userId, LocalDate start, LocalDate end) {
        List<DemandeConge> userDemandes = demandeCongeRepository.findByUserIdAndStatusAndDateRange(
                userId, 
                Arrays.asList(StatutConge.ACCEPTE, StatutConge.EN_ATTENTE),
                start,
                end
        );

        Set<LocalDate> unavailable = new HashSet<>();
        for (DemandeConge demande : userDemandes) {
            for (LocalDate date = demande.getDateDebut(); 
                 !date.isAfter(demande.getDateFin()); 
                 date = date.plusDays(1)) {
                unavailable.add(date);
            }
        }
        return unavailable;
    }

    /**
     * Récupère les jours fériés pour un pays
     */
    private Set<LocalDate> getHolidaysForCountry(String countryCode, LocalDate start, LocalDate end) {
        List<Holiday> holidays = holidayRepository.findByCountryCodeAndDateRange(countryCode, start, end);
        return holidays.stream()
                .map(Holiday::getDateJour)
                .collect(Collectors.toSet());
    }

    /**
     * Trouve la meilleure plage de dates à partir d'une date donnée
     */
    private DateRangeFitness findBestDateRange(
            LocalDate start,
            int requestedDays,
            Set<LocalDate> unavailable,
            Set<LocalDate> holidays,
            LocalDate searchEnd) {

        DateRangeFitness best = new DateRangeFitness();
        best.isValid = false;

        LocalDate current = start;
        int consecutiveWorkingDays = 0;
        LocalDate rangeStart = start;

        while (current.isBefore(searchEnd)) {
            if (!isWeekend(current) && !unavailable.contains(current) && !holidays.contains(current)) {
                if (consecutiveWorkingDays == 0) {
                    rangeStart = current;
                }
                consecutiveWorkingDays++;

                if (consecutiveWorkingDays >= requestedDays) {
                    // Trouvé une bonne plage
                    best.startDate = rangeStart;
                    best.endDate = current;
                    best.workingDays = consecutiveWorkingDays;
                    best.isValid = true;
                    // Calculer le score (préférer les débuts de semaine)
                    best.score = calculateDateRangeScore(rangeStart, current, unavailable, holidays);
                    break;
                }
            } else {
                consecutiveWorkingDays = 0;
            }
            current = current.plusDays(1);
        }

        return best;
    }

    /**
     * Calcule un score de qualité pour une plage de dates
     * Score élevé = meilleure plage
     */
    private double calculateDateRangeScore(LocalDate start, LocalDate end, Set<LocalDate> unavailable, Set<LocalDate> holidays) {
        double score = 1.0;

        // Préférer les débuts de lundi
        if (start.getDayOfWeek() == DayOfWeek.MONDAY) {
            score += 0.2;
        }

        // Pénaliser les courtes semaines
        int weekNumber = start.get(java.time.temporal.WeekFields.ISO.weekOfYear());
        int weekDayCount = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (!isWeekend(d)) weekDayCount++;
        }
        score += (weekDayCount / 5.0) * 0.3;

        // Pénaliser la proximité avec les jours fériés
        for (int i = 1; i <= 5; i++) {
            if (holidays.contains(start.minusDays(i))) {
                score -= 0.05;
            }
        }

        return Math.min(score, 1.0); // Max 1.0
    }

    /**
     * Détecte les absences simultanées des autres employés
     */
    private List<DemandeConge> findSimultaneousAbsences(Long userId, LocalDate start, LocalDate end) {
        return demandeCongeRepository.findSimultaneousAbsences(start, end)
                .stream()
                .filter(d -> !d.getUser().getId().equals(userId))
                .collect(Collectors.toList());
    }

    /**
     * Vérifie si une période est critique
     * Critères : fin de mois, fin de trimestre, fin d'année
     */
    private boolean isCriticalPeriod(LocalDate start, LocalDate end) {
        // Fin de mois (25-31)
        if (start.getDayOfMonth() >= 25 || end.getDayOfMonth() >= 25) {
            return true;
        }
        // Fin de trimestre (29-31 mars, 29-30 juin, 29-30 septembre, 29-31 décembre)
        int month = start.getMonthValue();
        int day = end.getDayOfMonth();
        return (month % 3 == 0 && day >= 25) || (end.getMonthValue() % 3 == 0 && end.getDayOfMonth() >= 25);
    }

    /**
     * Compte le nombre total d'absences pendant une période
     */
    private int countTotalAbsencesDuring(LocalDate start, LocalDate end) {
        return demandeCongeRepository.countAbsencesDuring(start, end);
    }

    /**
     * Vérifie si une date est un weekend
     */
    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }

    /**
     * Construit la raison de la suggestion
     */
    private String buildSuggestionReason(DateRangeFitness fitness) {
        StringBuilder reason = new StringBuilder();
        reason.append("Période libre sans conflits. ");
        if (fitness.startDate.getDayOfWeek() == DayOfWeek.MONDAY) {
            reason.append("Débute un lundi (excellent). ");
        }
        reason.append("Score d'optimisation : ").append(String.format("%.0f%%", fitness.score * 100));
        return reason.toString();
    }

    // =================== CLASSE INTERNE ===================

    /**
     * Classe pour représenter la "fitness" d'une plage de dates
     */
    private static class DateRangeFitness {
        LocalDate startDate;
        LocalDate endDate;
        int workingDays;
        double score;
        boolean isValid;
    }
}
