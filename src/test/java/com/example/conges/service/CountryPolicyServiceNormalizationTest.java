package com.example.conges.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test de Normalisation des Pays
 * Vérifies que backend traite les pays de la même manière que le frontend
 */
@SpringBootTest
@DisplayName("Country Policy Service - Normalization Tests")
class CountryPolicyServiceNormalizationTest {

    @Autowired
    private CountryPolicyService countryPolicyService;

    @Test
    @DisplayName("ISO2 codes valides")
    void testValidIso2Codes() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("TN"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("FR"));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("MA"));
    }

    @Test
    @DisplayName("ISO2 codes minuscules")
    void testLowercaseIso2Codes() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("tn"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("fr"));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("ma"));
    }

    @Test
    @DisplayName("ISO2 codes avec espaces")
    void testIso2CodesWithSpaces() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("  TN  "));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("  FR  "));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("  MA  "));
    }

    @Test
    @DisplayName("Noms complets en français")
    void testFrenchFullNames() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("Tunisie"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("France"));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("Maroc"));
    }

    @Test
    @DisplayName("Noms complets en anglais")
    void testEnglishFullNames() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("TUNISIA"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("FRANCE"));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("MOROCCO"));
    }

    @Test
    @DisplayName("Variantes avec casse mixte")
    void testMixedCaseVariants() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("Tunisia"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("France"));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("Morocco"));
    }

    @Test
    @DisplayName("Variantes contenant FRANC")
    void testFrancVariants() {
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("Français"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("FRANCAIS"));
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("FRANCIEN"));
    }

    @Test
    @DisplayName("Variantes contenant MAROC")
    void testMarocVariants() {
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("MAROC_OLD"));
        assertEquals("MA", countryPolicyService.normalizeBusinessCountry("Marocain"));
    }

    @Test
    @DisplayName("Outre-mer français")
    void testOverseasFrench() {
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("GP")); // Guadeloupe
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("MQ")); // Martinique
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("GF")); // Guyane
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("RE")); // Réunion
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("YT")); // Mayotte
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("PM")); // Saint-Pierre-et-Miquelon
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("BL")); // Saint-Barthélemy
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("MF")); // Saint-Martin
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("WF")); // Wallis-et-Futuna
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("PF")); // Polynésie française
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("NC")); // Nouvelle-Calédonie
        assertEquals("FR", countryPolicyService.normalizeBusinessCountry("TF")); // Terres australes françaises
    }

    @Test
    @DisplayName("Vides et nulls → Défaut TN")
    void testEmptyAndNullDefaults() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry(""));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("   "));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry(null));
    }

    @Test
    @DisplayName("Codes inconnus → Défaut TN")
    void testUnknownCodesDefaults() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("UK"));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("US"));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("DE"));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("ES"));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("belgique"));
    }

    @Test
    @DisplayName("Cas limites")
    void testEdgeCases() {
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("tunisia123"));
        assertEquals("TN", countryPolicyService.normalizeBusinessCountry("x")); // Un seul caractère
    }
}
