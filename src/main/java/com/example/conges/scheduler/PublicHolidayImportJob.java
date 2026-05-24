package com.example.conges.scheduler;

import com.example.conges.service.HrHolidayService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Synchronise automatiquement les jours fériés officiels (TN, FR, MA).
 *
 * - Le 1er décembre : pré-charge l'année suivante.
 * - Le 2 janvier   : recharge l'année courante (les données officielles sont parfois
 *                    publiées en retard pour certains pays).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PublicHolidayImportJob {

    private final HrHolidayService hrHolidayService;

    /** 1er décembre à 6h00 : importer l'année N+1 */
    @Scheduled(cron = "${public-holiday.import.cron-next-year:0 0 6 1 12 *}")
    public void importNextYear() {
        int nextYear = LocalDate.now().getYear() + 1;
        log.info("PublicHolidayImportJob : import jours fériés {}", nextYear);
        try {
            hrHolidayService.importPublicHolidaysAllCountries(nextYear);
            log.info("PublicHolidayImportJob : {} terminé", nextYear);
        } catch (Exception e) {
            log.warn("PublicHolidayImportJob : échec import {} — {}", nextYear, e.getMessage());
        }
    }

    /** 2 janvier à 6h00 : recharger l'année courante */
    @Scheduled(cron = "${public-holiday.import.cron-current-year:0 0 6 2 1 *}")
    public void importCurrentYear() {
        int currentYear = LocalDate.now().getYear();
        log.info("PublicHolidayImportJob : rafraîchissement jours fériés {}", currentYear);
        try {
            hrHolidayService.importPublicHolidaysAllCountries(currentYear);
            log.info("PublicHolidayImportJob : {} terminé", currentYear);
        } catch (Exception e) {
            log.warn("PublicHolidayImportJob : échec import {} — {}", currentYear, e.getMessage());
        }
    }
}
