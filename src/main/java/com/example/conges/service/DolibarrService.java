package com.example.conges.service;

import com.example.conges.dto.dolibarr.DolibarrEmployeeDto;
import com.example.conges.dto.dolibarr.DolibarrHolidayDto;
import com.example.conges.dto.dolibarr.DolibarrLeaveAllocationDto;
import com.example.conges.dto.dolibarr.DolibarrLeaveTypeDto;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.EmployeeLeaveAllocation;
import com.example.conges.entity.LeaveType;
import com.example.conges.entity.Role;
import com.example.conges.entity.SyncDirection;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.EmployeeLeaveAllocationRepository;
import com.example.conges.repository.LeaveTypeRepository;
import com.example.conges.repository.UserRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import javax.annotation.PostConstruct;

/**
 * Intégration Dolibarr : API REST et accès SQL optionnel sur la base Dolibarr.
 *
 * <p>Dolibarr sert de <strong>référentiel de stockage</strong> (types, utilisateurs, soldes {@code nb_holiday} / API
 * allocations). Il n’implémente pas les règles métier par pays ni les prorata : celles-ci sont calculées dans
 * l’application. Ce service se charge de lire/écrire ces montants et de les recopier localement après synchro.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DolibarrService {

    private static final int OUTBOUND_MAX_RETRIES = 1;

    private static final Map<String, String> ISO3166_ALPHA3_TO2 =
            Map.of("FRA", "FR", "TUN", "TN", "MAR", "MA");
    private static final Map<Integer, String> NUMERIC_ISO_TO_ALPHA2 =
            Map.of(250, "FR", 788, "TN", 504, "MA");
    private static final Set<String> FR_OVERSEAS_ISO2 =
            Set.of(
                    "GP", "MQ", "GF", "RE", "YT", "PM", "BL",
                    "MF", "WF", "PF", "NC", "TF"
            );

    private final UserRepository userRepository;
    private final EmployeeLeaveAllocationRepository employeeLeaveAllocationRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final DolibarrSyncLogService dolibarrSyncLogService;
    private final FranceRttLedgerService franceRttLedgerService;
    private final JdbcTemplate jdbcTemplate;
    @Qualifier("dolibarrJdbcTemplate")
    private final JdbcTemplate dolibarrJdbcTemplate;
    private RestTemplate restTemplate;

    @PostConstruct
    private void initRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(8_000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Value("${dolibarr.database.table-prefix:llx_}")
    private String dolibarrTablePrefix;

    @Value("${dolibarr.url:http://localhost/dolibarr/htdocs/api/index.php}")
    private String dolibarrUrl;

    @Value("${dolibarr.api-key:}")
    private String dolibarrApiKey;

    /**
     * Récupère la liste des employés depuis Dolibarr
     *
     * @return Liste des employés Dolibarr actifs
     */
    public List<DolibarrEmployeeDto> getEmployeesFromDolibarr() {
        if (!isDolibarrConfigured()) {
            log.warn("Dolibarr n'est pas configuré. Vérifiez dolibarr.url et dolibarr.api-key");
            return new ArrayList<>();
        }

        try {
            String url = dolibarrUrl + "/users?sortfield=rowid&sortorder=ASC&limit=100";
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<DolibarrEmployeeDto[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    DolibarrEmployeeDto[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Récupération de {} employés depuis Dolibarr", response.getBody().length);
                return List.of(response.getBody());
            }

            log.error("Erreur lors de la récupération des employés Dolibarr: {}", 
                     response.getStatusCode());
            return new ArrayList<>();

        } catch (RestClientException e) {
            log.error("Erreur de connexion à Dolibarr: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Récupère détail d'un employé de Dolibarr
     */
    public DolibarrEmployeeDto getEmployeeFromDolibarr(Long employeeId) {
        if (!isDolibarrConfigured()) {
            log.warn("Dolibarr n'est pas configuré");
            return null;
        }

        try {
            String url = dolibarrUrl + "/users/" + employeeId;
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<DolibarrEmployeeDto> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    DolibarrEmployeeDto.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Employé {} récupéré depuis Dolibarr", employeeId);
                return response.getBody();
            }

            log.error("Employé {} non trouvé dans Dolibarr", employeeId);
            return null;

        } catch (RestClientException e) {
            log.error("Erreur lors de la récupération de l'employé {} : {}", employeeId, e.getMessage());
            return null;
        }
    }

    /**
     * Synchronise les employés Dolibarr avec la base de données
     */
    @Transactional
    public int syncEmployeesFromDolibarr() {
        List<DolibarrEmployeeDto> dolibarrEmployees = getEmployeesFromDolibarr();

        if (dolibarrEmployees.isEmpty()) {
            log.warn("Aucun employé trouvé sur Dolibarr");
            return 0;
        }

        int syncCount = 0;

        for (DolibarrEmployeeDto doliEmployee : dolibarrEmployees) {
            // Filtre les employés inactifs
            if (!doliEmployee.isActive()) {
                log.debug("Employé {} est inactif, ignoré", doliEmployee.getEmail());
                continue;
            }

            // Vérifie si l'employé existe déjà en local
            UserEntity existingUser = userRepository.findByDolibarrId(doliEmployee.getId())
                    .orElse(null);

            String resolvedEmail = resolveEmployeeEmail(doliEmployee.getEmail(), doliEmployee.getId());
            Role resolvedRole = resolveRole(existingUser, doliEmployee, resolvedEmail);

            if (existingUser != null) {
                // Mise à jour de l'employé existant
                existingUser.setNom(doliEmployee.getLastName());
                existingUser.setPrenom(doliEmployee.getFirstName());
                // Ne jamais écraser par une valeur vide (sinon violation unique + perte d'info).
                if (resolvedEmail != null && !resolvedEmail.isBlank()) {
                    existingUser.setEmail(resolvedEmail);
                }
                existingUser.setPays(
                        normalizeToSupportedHrPays(
                                resolveCountryCode(doliEmployee.getId(), doliEmployee)));
                // Important: si l'utilisateur est Super Admin dans Dolibarr, on le reflète localement
                // afin que le champ « Approuvé par » liste tous les comptes admin Dolibarr.
                existingUser.setRole(resolvedRole);
                userRepository.save(existingUser);
                dolibarrSyncLogService.logSuccess(
                        "USER",
                        "UPSERT",
                        existingUser.getId(),
                        doliEmployee.getId(),
                        SyncDirection.INBOUND,
                        doliEmployee
                );
                log.debug("Employé {} mis à jour", doliEmployee.getEmail());
                syncCount++;
            } else {
                // Création d'un nouvel employé
                UserEntity newUser = UserEntity.builder()
                        .dolibarrId(doliEmployee.getId())
                        .email(resolvedEmail)
                        .nom(doliEmployee.getLastName())
                        .prenom(doliEmployee.getFirstName())
                        .pays(normalizeToSupportedHrPays(
                                resolveCountryCode(doliEmployee.getId(), doliEmployee)))
                        .role(resolvedRole)
                        .build();

                userRepository.save(newUser);
                dolibarrSyncLogService.logSuccess(
                        "USER",
                        "UPSERT",
                        newUser.getId(),
                        doliEmployee.getId(),
                        SyncDirection.INBOUND,
                        doliEmployee
                );
                log.info("Nouvel employé créé: {} {}", 
                        doliEmployee.getFirstName(), doliEmployee.getLastName());
                syncCount++;
            }
        }

        log.info("Synchronisation Dolibarr complétée: {} employés synchronisés", syncCount);
        return syncCount;
    }

    /**
     * Détermine le rôle local à partir d'un user Dolibarr.
     * <p>Objectif: exposer correctement les comptes "Super Admin" Dolibarr via /api/hr/admins pour le champ
     * « Approuvé par ».
     */
    private Role resolveRole(UserEntity existingUser, DolibarrEmployeeDto dolibarrUser, String email) {
        if (existingUser != null
                && existingUser.getRole() != null
                && existingUser.getRole() != Role.EMPLOYE) {
            // Si un rôle RH/ADMIN local a déjà été validé, on le conserve.
            return existingUser.getRole();
        }
        if (dolibarrUser != null && dolibarrUser.isAdminLike()) {
            return Role.ADMIN;
        }
        String login = dolibarrUser == null ? "" : String.valueOf(dolibarrUser.getLogin()).toLowerCase(Locale.ROOT);
        String normalizedEmail = email == null ? "" : email.toLowerCase(Locale.ROOT);
        if (login.contains("admin") || normalizedEmail.contains("admin")) {
            return Role.ADMIN;
        }
        return Role.EMPLOYE;
    }

    private String resolveEmployeeEmail(String rawEmail, Long dolibarrId) {
        String email = rawEmail != null ? rawEmail.trim() : "";
        if (!email.isEmpty()) {
            return email;
        }
        // Dolibarr peut renvoyer un utilisateur sans email (ou email vide). Le schéma local impose email UNIQUE NOT NULL,
        // donc on génère une valeur stable et unique (par Dolibarr ID).
        if (dolibarrId == null) {
            // On ne peut pas générer un email unique stable -> on skip en amont si ça arrive
            return "unknown-" + System.currentTimeMillis() + "@invalid.local";
        }
        return "dolibarr-" + dolibarrId + "@invalid.local";
    }

    /**
     * Récupère un employé par son email Dolibarr et le synchronise
     */
    @Transactional
    public UserEntity syncEmployeeByEmail(String email) {
        // D'abord, vérifier si l'employé existe déjà localement
        UserEntity existingUser = userRepository.findByEmail(email)
                .orElse(null);

        if (existingUser != null && existingUser.getDolibarrId() != null) {
            log.debug("Employé {} existe déjà localement avec ID Dolibarr {}", 
                     email, existingUser.getDolibarrId());
            return existingUser;
        }

        // Récupérer tous les employés de Dolibarr et chercher celui avec cet email
        List<DolibarrEmployeeDto> employees = getEmployeesFromDolibarr();
        DolibarrEmployeeDto foundEmployee = employees.stream()
                .filter(emp -> emp.getEmail() != null && emp.getEmail().equals(email))
                .findFirst()
                .orElse(null);

        if (foundEmployee == null) {
            log.warn("Employé avec email {} non trouvé sur Dolibarr", email);
            return null;
        }

        // Synchroniser l'employé trouvé
        if (existingUser != null) {
            existingUser.setDolibarrId(foundEmployee.getId());
            existingUser.setNom(foundEmployee.getLastName());
            existingUser.setPrenom(foundEmployee.getFirstName());
            existingUser.setPays(normalizeToSupportedHrPays(
                    resolveCountryCode(foundEmployee.getId(), foundEmployee)));
            existingUser.setRole(resolveRole(existingUser, foundEmployee, email));
            userRepository.save(existingUser);
            log.info("Employé {} lié à Dolibarr (ID: {})", email, foundEmployee.getId());
            return existingUser;
        } else {
            UserEntity newUser = UserEntity.builder()
                    .dolibarrId(foundEmployee.getId())
                    .email(email)
                    .nom(foundEmployee.getLastName())
                    .prenom(foundEmployee.getFirstName())
                    .pays(normalizeToSupportedHrPays(
                            resolveCountryCode(foundEmployee.getId(), foundEmployee)))
                    .role(resolveRole(null, foundEmployee, email))
                    .build();
            userRepository.save(newUser);
            log.info("Nouvel employé créé et synchronisé avec Dolibarr: {}", email);
            return newUser;
        }
    }

    /**
     * Récupère la liste des types de congés depuis Dolibarr
     */
    public List<DolibarrLeaveTypeDto> getLeaveTypesFromDolibarr() {
        if (!isDolibarrConfigured()) {
            log.warn("Dolibarr n'est pas configuré");
            return new ArrayList<>();
        }

        try {
            // Note: Le endpoint varie selon la version Dolibarr
            // Peut être: /leavetypes, /holidays/types, /hrm/leavetypes
            String url = dolibarrUrl + "/leavetypes?sortfield=rowid&sortorder=ASC&limit=100";
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<DolibarrLeaveTypeDto[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    DolibarrLeaveTypeDto[].class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Récupération de {} types de congés depuis Dolibarr", response.getBody().length);
                return List.of(response.getBody());
            }

            log.warn("Aucun type de congé trouvé sur Dolibarr");
            return new ArrayList<>();

        } catch (RestClientException e) {
            log.error("Erreur lors de la récupération des types de congés: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Fallback DB: types de congés depuis la table dictionnaire Dolibarr (llx_c_holiday_types).
     * Utile quand les endpoints REST varient selon versions/modules.
     */
    public List<DolibarrLeaveTypeDto> getLeaveTypesFromDolibarrDb() {
        String prefix = (dolibarrTablePrefix == null || dolibarrTablePrefix.isBlank()) ? "llx_" : dolibarrTablePrefix;
        String sql = "SELECT rowid, code, label, active FROM " + prefix + "c_holiday_types";
        return dolibarrJdbcTemplate.query(sql, (rs, __) -> {
            DolibarrLeaveTypeDto dto = new DolibarrLeaveTypeDto();
            dto.setId(rs.getLong("rowid"));
            dto.setCode(rs.getString("code"));
            dto.setLibelle(rs.getString("label"));
            dto.setActive(rs.getInt("active"));
            return dto;
        });
    }

    /**
     * Récupère la liste des jours fériés depuis Dolibarr
     */
    public List<DolibarrHolidayDto> getHolidaysFromDolibarr() {
        if (!isDolibarrConfigured()) {
            log.warn("Dolibarr n'est pas configuré");
            return new ArrayList<>();
        }

        HttpHeaders headers = createHeaders();
        HttpEntity<?> entity = new HttpEntity<>(headers);
        List<String> attempts = List.of(
                dolibarrUrl + "/holidays?sortfield=rowid&sortorder=ASC&limit=500",
                dolibarrUrl + "/bank_holidays?sortfield=rowid&sortorder=ASC&limit=500",
                dolibarrUrl + "/leaves/holidays?sortfield=rowid&sortorder=ASC&limit=500"
        );

        RestClientException last = null;
        for (String url : attempts) {
            try {
                ResponseEntity<DolibarrHolidayDto[]> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        DolibarrHolidayDto[].class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    log.info("Récupération de {} jours fériés depuis Dolibarr", response.getBody().length);
                    return List.of(response.getBody());
                }
            } catch (RestClientException e) {
                last = e;
            }
        }

        log.warn("Aucun jour férié trouvé sur Dolibarr{}", last != null ? (": " + last.getMessage()) : "");
        return new ArrayList<>();
    }

    /**
     * Récupère les allocations depuis l’API REST Dolibarr (toutes, ou filtre optionnel fk_user selon versions).
     */
    public List<DolibarrLeaveAllocationDto> getLeaveAllocationsFromDolibarr() {
        // L'API REST peut être partielle selon modules/versions (souvent uniquement CP). On fusionne avec la DB.
        List<DolibarrLeaveAllocationDto> fromApi = fetchLeaveAllocationsFromDolibarrApi(null);
        List<DolibarrLeaveAllocationDto> fromDb;
        try {
            fromDb = fetchLeaveAllocationsFromDolibarrDb();
        } catch (RuntimeException ex) {
            log.warn("Fallback DB allocations Dolibarr indisponible: {}", ex.getMessage());
            fromDb = List.of();
        }

        if ((fromApi == null || fromApi.isEmpty()) && (fromDb == null || fromDb.isEmpty())) {
            return List.of();
        }

        int year = java.time.Year.now().getValue();
        Map<String, DolibarrLeaveAllocationDto> merged = new LinkedHashMap<>();
        for (DolibarrLeaveAllocationDto a : (fromApi == null ? List.<DolibarrLeaveAllocationDto>of() : fromApi)) {
            merged.put(allocMergeKey(a, year), sanitizeAllocationDates(a, year));
        }
        // DB doit écraser l'API quand les deux existent (Dolibarr holiday_users = source compteur).
        for (DolibarrLeaveAllocationDto a : (fromDb == null ? List.<DolibarrLeaveAllocationDto>of() : fromDb)) {
            merged.put(allocMergeKey(a, year), sanitizeAllocationDates(a, year));
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Tente plusieurs variantes REST ; si filtre fk_user refuse côté Dolibarr, repli liste complète puis filtre local.
     */
    private List<DolibarrLeaveAllocationDto> fetchLeaveAllocationsFromDolibarrApi(Long fkUserDolibarr) {
        if (!isDolibarrConfigured()) {
            log.warn("Dolibarr n'est pas configuré");
            return new ArrayList<>();
        }
        HttpHeaders headers = createHeaders();
        HttpEntity<?> entity = new HttpEntity<>(headers);

        List<String> attempts = new ArrayList<>();
        if (fkUserDolibarr != null) {
            try {
                attempts.add(dolibarrUrl + "/leaves/allocations?sortfield=rowid&sortorder=ASC&limit=5000&sqlfilters="
                        + URLEncoder.encode("(t.fk_user:=:" + fkUserDolibarr + ")", StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // URLEncoder ne devrait pas échouer sur UTF-8
            }
        }
        attempts.add(dolibarrUrl + "/leaves/allocations?sortfield=rowid&sortorder=ASC&limit=5000");

        RestClientException last = null;
        for (String url : attempts) {
            try {
                ResponseEntity<DolibarrLeaveAllocationDto[]> response = restTemplate.exchange(
                        url,
                        HttpMethod.GET,
                        entity,
                        DolibarrLeaveAllocationDto[].class);

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    List<DolibarrLeaveAllocationDto> list = List.of(response.getBody());
                    if (fkUserDolibarr != null) {
                        list = list.stream()
                                .filter(a -> fkUserDolibarr.equals(a.getEmployeeId()))
                                .toList();
                    }
                    if (!list.isEmpty() || fkUserDolibarr == null) {
                        log.debug("REST allocations Dolibarr: {} lignes", list.size());
                        return new ArrayList<>(list);
                    }
                }
            } catch (RestClientException e) {
                last = e;
            }
        }

        log.debug("REST allocations Dolibarr indisponibles: {}", last == null ? "aucune réponse utile" : last.getMessage());
        return new ArrayList<>();
    }

    /**
     * Fallback DB: soldes depuis llx_holiday_users (nb_holiday) + types llx_c_holiday_types.
     * On projette ces soldes en DolibarrLeaveAllocationDto pour réutiliser la même pipeline de sync.
     */
    private List<DolibarrLeaveAllocationDto> fetchLeaveAllocationsFromDolibarrDb() {
        String prefix = (dolibarrTablePrefix == null || dolibarrTablePrefix.isBlank()) ? "llx_" : dolibarrTablePrefix;
        String sql = "SELECT hu.fk_user, hu.fk_type, hu.nb_holiday " +
                "FROM " + prefix + "holiday_users hu";
        int year = java.time.Year.now().getValue();
        return dolibarrJdbcTemplate.query(sql, (rs, __) -> {
            long userId = rs.getLong("fk_user");
            long typeId = rs.getLong("fk_type");
            double balance = rs.getDouble("nb_holiday");
            DolibarrLeaveAllocationDto dto = new DolibarrLeaveAllocationDto();
            dto.setEmployeeId(userId);
            dto.setTypeCongeId(typeId);
            dto.setJoursDisponibles(balance);
            dto.setJoursInitiaux(balance);
            dto.setJoursUtilises(0d);
            dto.setAnnee(year);
            dto.setActive(1);
            // Fenêtre annuelle obligatoire pour employee_leave_allocations (NOT NULL en base)
            dto.setDateDebut(java.time.LocalDate.of(year, 1, 1));
            dto.setDateFin(java.time.LocalDate.of(year, 12, 31));
            // Stable synthetic ID (unique within instance)
            dto.setId(userId * 1000000L + typeId);
            return dto;
        });
    }

    public long dolibarrDbHolidayUsersCount() {
        String prefix = (dolibarrTablePrefix == null || dolibarrTablePrefix.isBlank()) ? "llx_" : dolibarrTablePrefix;
        String sql = "SELECT COUNT(*) FROM " + prefix + "holiday_users";
        Long v = dolibarrJdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0L : v;
    }

    /**
     * Teste la connexion à Dolibarr
     */
    public boolean testDolibarrConnection() {
        if (!isDolibarrConfigured()) {
            return false;
        }

        try {
            String url = dolibarrUrl + "/users?limit=1";
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<DolibarrEmployeeDto[]> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    DolibarrEmployeeDto[].class
            );

            boolean success = response.getStatusCode().is2xxSuccessful();
            log.info("Test de connexion Dolibarr: {}", success ? "✅ Succès" : "❌ Échoué");
            return success;

        } catch (RestClientException e) {
            log.error("Erreur lors du test de connexion Dolibarr: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Synchronisation sortante: envoie une demande locale vers Dolibarr.
     */
    public Long pushLeaveRequest(DemandeConge demande) {
        if (!isDolibarrConfigured()) {
            dolibarrSyncLogService.logFailure(
                    "LEAVE_REQUEST",
                    "CREATE",
                    demande.getId(),
                    null,
                    SyncDirection.OUTBOUND,
                    "Dolibarr non configuré",
                    null
            );
            return null;
        }
        try {
            String url = dolibarrUrl + "/leaves";
            HttpHeaders headers = createHeaders();
            Map<String, Object> payload = new HashMap<>();
            payload.put("fk_user", demande.getUser().getDolibarrId());
            payload.put("date_debut", demande.getDateDebut().toString());
            payload.put("date_fin", demande.getDateFin().toString());
            payload.put("type_code", demande.getTypeConge().name());
            payload.put("note", demande.getMotif());
            payload.put("status", demande.getStatut().name());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().get("id") != null) {
                Long remoteId = Long.valueOf(response.getBody().get("id").toString());
                dolibarrSyncLogService.logSuccess(
                        "LEAVE_REQUEST",
                        "CREATE",
                        demande.getId(),
                        remoteId,
                        SyncDirection.OUTBOUND,
                        payload
                );
                return remoteId;
            }
            dolibarrSyncLogService.logFailure(
                    "LEAVE_REQUEST",
                    "CREATE",
                    demande.getId(),
                    null,
                    SyncDirection.OUTBOUND,
                    "Réponse Dolibarr invalide",
                    response.getBody()
            );
            return null;
        } catch (Exception ex) {
            dolibarrSyncLogService.logFailure(
                    "LEAVE_REQUEST",
                    "CREATE",
                    demande.getId(),
                    null,
                    SyncDirection.OUTBOUND,
                    ex.getMessage(),
                    null
            );
            return null;
        }
    }

    /**
     * Authentifie un utilisateur directement via la base de données Dolibarr.
     * Contourne le endpoint REST /login qui est souvent bloqué (403) dans certaines configurations.
     * Supporte MD5, SHA-256 et BCrypt (tous les formats de hash Dolibarr).
     */
    public DolibarrEmployeeDto authenticateViaDolibarrDb(String loginOrEmail, String password) {
        if (loginOrEmail == null || loginOrEmail.isBlank() || password == null || password.isBlank()) {
            return null;
        }
        try {
            String prefix = (dolibarrTablePrefix == null || dolibarrTablePrefix.isBlank()) ? "llx_" : dolibarrTablePrefix;
            String sql = "SELECT rowid, login, email, pass, pass_crypted, pass_encoding FROM " + prefix + "user "
                    + "WHERE (LOWER(email) = LOWER(?) OR LOWER(login) = LOWER(?)) AND statut = 1 LIMIT 1";

            List<Map<String, Object>> rows = dolibarrJdbcTemplate.queryForList(sql, loginOrEmail, loginOrEmail);
            if (rows.isEmpty()) {
                log.debug("DB auth: aucun utilisateur actif trouvé pour '{}'", loginOrEmail);
                return null;
            }

            Map<String, Object> row = rows.get(0);
            // Dolibarr stores BCrypt in pass_crypted; legacy plain/MD5 in pass
            String passCrypted = row.get("pass_crypted") != null ? String.valueOf(row.get("pass_crypted")) : "";
            String passPlain   = row.get("pass") != null ? String.valueOf(row.get("pass")) : "";
            String storedHash  = !passCrypted.isBlank() ? passCrypted : passPlain;
            String encoding    = row.get("pass_encoding") != null ? String.valueOf(row.get("pass_encoding")).toLowerCase() : "";
            String login      = row.get("login") != null ? String.valueOf(row.get("login")) : loginOrEmail;
            Long   userId     = row.get("rowid") != null ? Long.valueOf(row.get("rowid").toString()) : null;

            log.info("🔍 DB auth: user='{}' login='{}' encoding='{}' hashLen={}", loginOrEmail, login, encoding, storedHash.length());
            boolean matches = verifyDolibarrPassword(password, storedHash, encoding, login);
            if (!matches) {
                log.info("❌ DB auth: mot de passe incorrect pour '{}' (encoding={})", loginOrEmail, encoding);
                return null;
            }

            log.info("✅ Authentification DB Dolibarr réussie pour login={}", login);
            // Récupérer le profil complet depuis l'API REST (admin key)
            DolibarrEmployeeDto emp = userId != null ? getEmployeeFromDolibarrById(userId) : null;
            if (emp == null) emp = findEmployeeByEmail(loginOrEmail);
            if (emp == null) emp = findEmployeeByLogin(login);
            if (emp == null) {
                // Construire un DTO minimal depuis les données DB
                emp = new DolibarrEmployeeDto();
                emp.setId(userId);
                emp.setLogin(login);
                String dbEmail = row.get("email") != null ? String.valueOf(row.get("email")) : "";
                emp.setEmail(dbEmail.isBlank() ? loginOrEmail : dbEmail);
            }
            return emp;
        } catch (Exception ex) {
            log.debug("Authentification DB Dolibarr indisponible pour '{}': {}", loginOrEmail, ex.getMessage());
            return null;
        }
    }

    private boolean verifyDolibarrPassword(String password, String storedHash, String encoding, String login) {
        if (storedHash == null || storedHash.isBlank()) return false;
        // BCrypt (stocké tel quel avec préfixe $2y$ ou $2a$)
        if (storedHash.startsWith("$2") || encoding.contains("bcrypt")) {
            try {
                return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .matches(password, storedHash);
            } catch (Exception e) { return false; }
        }
        // SHA-256 : Dolibarr v14+ (formats: sha256(pass) ou sha256(login+pass) ou sha256(entity+pass))
        if (encoding.contains("sha256") || encoding.contains("sha-256")) {
            return sha256(password).equalsIgnoreCase(storedHash)
                    || sha256(login + password).equalsIgnoreCase(storedHash)
                    || sha256("1" + password).equalsIgnoreCase(storedHash);
        }
        // MD5 : Dolibarr < v14 ou config legacy
        if (encoding.isBlank() || encoding.contains("md5")) {
            return md5(password).equalsIgnoreCase(storedHash)
                    || md5(login + password).equalsIgnoreCase(storedHash);
        }
        // SHA-1
        if (encoding.contains("sha1") || encoding.contains("sha-1")) {
            return sha1(password).equalsIgnoreCase(storedHash);
        }
        return false;
    }

    private String sha256(String input) {
        return hashWith("SHA-256", input);
    }
    private String sha1(String input) {
        return hashWith("SHA-1", input);
    }
    private String md5(String input) {
        return hashWith("MD5", input);
    }
    private String hashWith(String algo, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public DolibarrEmployeeDto authenticateUserViaApi(String email, String password) {
        if (!isDolibarrConfigured()) {
            return null;
        }

        // Pré-résoudre le login name si l'utilisateur a saisi une adresse e-mail
        String loginName = email;
        if (email != null && email.contains("@")) {
            DolibarrEmployeeDto byEmail = findEmployeeByEmail(email);
            if (byEmail != null && byEmail.getLogin() != null && !byEmail.getLogin().isBlank()) {
                loginName = byEmail.getLogin();
                log.debug("Email {} résolu en login Dolibarr={}", email, loginName);
            }
        }

        // Essayer avec le login name résolu, puis fallback sur l'email original
        List<String> credentialsToTry = loginName.equalsIgnoreCase(email)
                ? List.of(email)
                : List.of(loginName, email);

        List<String> candidateEndpoints = List.of("/login", "/auth/login");

        for (String credential : credentialsToTry) {
            for (String endpoint : candidateEndpoints) {
                try {
                    String url = dolibarrUrl + endpoint;
                    Map<String, Object> loginResponse = tryDolibarrLogin(url, credential, password);
                    if (loginResponse != null) {
                        log.info("✅ Authentification Dolibarr réussie pour email={}, login={}", email, credential);

                        // 1. Extraire l'ID depuis la réponse (certaines versions Dolibarr le fournissent)
                        Long userId = extractUserIdFromLoginResponse(loginResponse);
                        if (userId != null) {
                            DolibarrEmployeeDto userFromId = getEmployeeFromDolibarrById(userId);
                            if (userFromId != null) return userFromId;
                        }

                        // 2. Utiliser la dolapikey personnelle de l'utilisateur retournée par /login
                        String userApiKey = extractDoliapikeyFromLoginResponse(loginResponse);
                        if (userApiKey != null) {
                            DolibarrEmployeeDto userFromOwnKey = findEmployeeWithApiKey(credential, userApiKey);
                            if (userFromOwnKey != null) return userFromOwnKey;
                        }

                        // 3. Chercher par email (avec clé admin)
                        DolibarrEmployeeDto userByEmail = findEmployeeByEmail(email);
                        if (userByEmail != null) return userByEmail;

                        // 4. Chercher par login Dolibarr (avec clé admin)
                        DolibarrEmployeeDto userByLogin = findEmployeeByLogin(credential);
                        if (userByLogin != null) return userByLogin;

                        log.warn("⚠️ Auth Dolibarr OK pour credential={} mais utilisateur introuvable", credential);
                        return null;
                    }
                } catch (Exception ex) {
                    log.debug("Dolibarr login endpoint {} échoué pour login={}: {}", endpoint, credential, ex.getMessage());
                }
            }
        }

        log.warn("❌ Authentification Dolibarr échouée pour email={}", email);
        return null;
    }

    private String extractDoliapikeyFromLoginResponse(Map<String, Object> loginResponse) {
        if (loginResponse == null) return null;
        // Format v14+: {"success": {"dolapikey": "xxx"}}
        Object success = loginResponse.get("success");
        if (success instanceof Map) {
            Object key = ((Map<?, ?>) success).get("dolapikey");
            if (key != null && !String.valueOf(key).isBlank()) return String.valueOf(key);
        }
        // Format ancien: {"dolapikey": "xxx"}
        Object key = loginResponse.get("dolapikey");
        if (key != null && !String.valueOf(key).isBlank()) return String.valueOf(key);
        return null;
    }

    private DolibarrEmployeeDto findEmployeeWithApiKey(String loginOrEmail, String apiKey) {
        if (loginOrEmail == null || apiKey == null || !isDolibarrConfigured()) return null;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("DOLAPIKEY", apiKey);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<?> entity = new HttpEntity<>(headers);

            // Essayer /users/me (Dolibarr v15+)
            try {
                ResponseEntity<DolibarrEmployeeDto> meResp = restTemplate.exchange(
                        dolibarrUrl + "/users/me", HttpMethod.GET, entity, DolibarrEmployeeDto.class);
                if (meResp.getStatusCode().is2xxSuccessful() && meResp.getBody() != null) {
                    log.debug("✅ Profil récupéré via /users/me pour login={}", loginOrEmail);
                    return meResp.getBody();
                }
            } catch (Exception ignored) {}

            // Fallback: /users?login=xxx avec la clé de l'utilisateur
            String encoded = URLEncoder.encode(loginOrEmail, StandardCharsets.UTF_8);
            ResponseEntity<DolibarrEmployeeDto[]> resp = restTemplate.exchange(
                    dolibarrUrl + "/users?login=" + encoded + "&limit=5",
                    HttpMethod.GET, entity, DolibarrEmployeeDto[].class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null && resp.getBody().length > 0) {
                return resp.getBody()[0];
            }
        } catch (Exception ex) {
            log.debug("findEmployeeWithApiKey échoué pour login={}: {}", loginOrEmail, ex.getMessage());
        }
        return null;
    }

    /**
     * Authentifie un utilisateur Dolibarr via HTTP Basic Auth (login:password).
     * Plus fiable que le endpoint /login qui peut être désactivé ou avoir un format variable.
     */
    public DolibarrEmployeeDto authenticateViaBasicAuth(String loginName, String password) {
        if (loginName == null || loginName.isBlank() || password == null || !isDolibarrConfigured()) {
            return null;
        }
        try {
            String creds = loginName + ":" + password;
            String basicEncoded = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + basicEncoded);
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            HttpEntity<?> entity = new HttpEntity<>(headers);

            // Essayer /users/me en priorité (accessible à tout utilisateur authentifié, Dolibarr v15+)
            try {
                ResponseEntity<DolibarrEmployeeDto> meResp = restTemplate.exchange(
                        dolibarrUrl + "/users/me", HttpMethod.GET, entity, DolibarrEmployeeDto.class);
                if (meResp.getStatusCode().is2xxSuccessful() && meResp.getBody() != null) {
                    log.info("✅ Basic Auth Dolibarr OK via /users/me pour login={}", loginName);
                    return meResp.getBody();
                }
            } catch (Exception ignored) {}

            // Fallback : /users?login=xxx (nécessite permission lecture utilisateurs)
            String urlEncoded = URLEncoder.encode(loginName, StandardCharsets.UTF_8);
            ResponseEntity<DolibarrEmployeeDto[]> response = restTemplate.exchange(
                    dolibarrUrl + "/users?login=" + urlEncoded + "&limit=1",
                    HttpMethod.GET, entity, DolibarrEmployeeDto[].class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && response.getBody().length > 0) {
                log.info("✅ Basic Auth Dolibarr OK via /users?login pour login={}", loginName);
                return response.getBody()[0];
            }
        } catch (Exception ex) {
            log.debug("Basic Auth Dolibarr échoué pour login={}: {}", loginName, ex.getMessage());
        }
        return null;
    }

    private DolibarrEmployeeDto findEmployeeByLogin(String loginName) {
        if (loginName == null || loginName.isBlank() || !isDolibarrConfigured()) return null;
        try {
            String encoded = URLEncoder.encode(loginName, StandardCharsets.UTF_8);
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<DolibarrEmployeeDto[]> response = restTemplate.exchange(
                    dolibarrUrl + "/users?login=" + encoded + "&limit=5",
                    HttpMethod.GET, entity, DolibarrEmployeeDto[].class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null
                    && response.getBody().length > 0) {
                return response.getBody()[0];
            }
        } catch (Exception ex) {
            log.debug("findEmployeeByLogin({}) échoué: {}", loginName, ex.getMessage());
        }
        // Fallback : parcourir la liste complète et comparer le login
        try {
            List<DolibarrEmployeeDto> all = getEmployeesFromDolibarr();
            return all.stream()
                    .filter(e -> loginName.equalsIgnoreCase(e.getLogin()) || loginName.equalsIgnoreCase(e.getEmail()))
                    .findFirst().orElse(null);
        } catch (Exception ex) {
            log.debug("findEmployeeByLogin fallback échoué pour {}: {}", loginName, ex.getMessage());
        }
        return null;
    }

    private Map<String, Object> tryDolibarrLogin(String url, String email, String password) {
        List<Map<String, String>> payloadCandidates = List.of(
                Map.of("login", email, "password", password),
                Map.of("username", email, "password", password),
                Map.of("email", email, "password", password)
        );

        for (Map<String, String> payload : payloadCandidates) {
            try {
                // L'endpoint /login ne doit pas recevoir DOLAPIKEY — il vérifie login/mdp et retourne un token
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                HttpEntity<Map<String, String>> entity = new HttpEntity<>(payload, headers);
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody();
                }
            } catch (Exception ex) {
                log.debug("Dolibarr login JSON payload {} failed for {}: {}", payload.keySet(), url, ex.getMessage());
            }

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                payload.forEach(form::add);
                HttpEntity<MultiValueMap<String, String>> formEntity = new HttpEntity<>(form, headers);
                ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, formEntity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    return response.getBody();
                }
            } catch (Exception ex) {
                log.debug("Dolibarr login form payload {} failed for {}: {}", payload.keySet(), url, ex.getMessage());
            }
        }

        return null;
    }

    /**
     * Extrait l'ID utilisateur de la réponse du login Dolibarr
     * Dolibarr peut retourner: {"id": 123, "user_id": 123, "rowid": 123, ...}
     */
    private Long extractUserIdFromLoginResponse(Map<String, Object> loginResponse) {
        if (loginResponse == null) {
            return null;
        }
        
        // Essayer les clés courantes
        for (String key : List.of("id", "user_id", "userid", "rowid", "userId")) {
            Object value = loginResponse.get(key);
            if (value != null) {
                try {
                    return Long.parseLong(value.toString());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    /**
     * Récupère un employé Dolibarr par son ID via l'API
     */
    private DolibarrEmployeeDto getEmployeeFromDolibarrById(Long employeeId) {
        if (employeeId == null || !isDolibarrConfigured()) {
            return null;
        }
        
        try {
            String url = dolibarrUrl + "/users/" + employeeId;
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);
            
            ResponseEntity<DolibarrEmployeeDto> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    DolibarrEmployeeDto.class
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.debug("✅ Utilisateur {} récupéré depuis Dolibarr API", employeeId);
                return response.getBody();
            }
        } catch (Exception ex) {
            log.debug("Erreur lors de la récupération de l'utilisateur {} : {}", employeeId, ex.getMessage());
        }
        
        return null;
    }

    /**
     * Cherche un employé par son email dans la liste Dolibarr
     * Avec gestion améliorée de la pagination
     */
    private DolibarrEmployeeDto findEmployeeByEmail(String email) {
        if (email == null || email.isBlank() || !isDolibarrConfigured()) {
            return null;
        }

        try {
            // Filtre SQL Dolibarr (v13+) : syntaxe correcte pour filtrer par email
            String sqlfilter = URLEncoder.encode("(t.email:=:'" + email + "')", StandardCharsets.UTF_8);
            String url = dolibarrUrl + "/users?sqlfilters=" + sqlfilter + "&limit=5";
            HttpHeaders headers = createHeaders();
            HttpEntity<?> entity = new HttpEntity<>(headers);
            ResponseEntity<DolibarrEmployeeDto[]> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, DolibarrEmployeeDto[].class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                for (DolibarrEmployeeDto dto : response.getBody()) {
                    if (email.equalsIgnoreCase(dto.getEmail())) return dto;
                }
            }
        } catch (Exception ex) {
            log.debug("Recherche sqlfilters par email échouée: {}", ex.getMessage());
        }
        
        // Fallback: récupérer la liste complète (avec pagination si nécessaire)
        try {
            List<DolibarrEmployeeDto> employees = getEmployeesFromDolibarr();
            return employees.stream()
                    .filter(emp -> emp.getEmail() != null && emp.getEmail().equalsIgnoreCase(email))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Impossible de trouver l'utilisateur par email {}: {}", email, ex.getMessage());
            return null;
        }
    }

    public DolibarrEmployeeDto findEmployeeByCredential(String credential) {
        if (credential == null || credential.isBlank() || !isDolibarrConfigured()) {
            return null;
        }

        DolibarrEmployeeDto byEmail = findEmployeeByEmail(credential);
        if (byEmail != null) {
            return byEmail;
        }

        try {
            List<DolibarrEmployeeDto> employees = getEmployeesFromDolibarr();
            return employees.stream()
                    .filter(emp -> emp.getLogin() != null && emp.getLogin().equalsIgnoreCase(credential))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Impossible de trouver l'utilisateur par identifiant {}: {}", credential, ex.getMessage());
            return null;
        }
    }

    /**
     * Outbound sync when a workflow completes approval.
     * Keeps Dolibarr as source-of-truth: status + allocation.
     * REQUIRES_NEW: sync failure must never roll back the caller's decision transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean syncApprovedLeave(DemandeConge demande) {
        if (demande == null || demande.getUser() == null) {
            return false;
        }
        boolean leaveUpdated = true;
        if (demande.getDolibarrLeaveRequestId() != null) {
            leaveUpdated = updateLeaveStatus(demande.getDolibarrLeaveRequestId(), "APPROVED", demande);
        }
        franceRttLedgerService.consumeFranceRttLedgerOnApprovedLeave(demande);
        if (franceRttLedgerService.shouldSkipDolibarrConsumption(demande)) {
            return leaveUpdated;
        }
        boolean allocationUpdated = consumeAllocationForApprovedLeave(demande);
        return leaveUpdated && allocationUpdated;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refreshAllocationsForUser(UserEntity user, int year) {
        if (user == null || user.getDolibarrId() == null || !isDolibarrConfigured()) {
            return;
        }
        try {
            List<DolibarrLeaveAllocationDto> merged = mergeJdbcFirstAllocationsForUser(user.getDolibarrId(), year);

            for (DolibarrLeaveAllocationDto remote : merged) {
                if (!remote.isActive()) {
                    continue;
                }
                Long fkTypeRef = remote.getTypeCongeId();
                if (fkTypeRef == null) {
                    continue;
                }

                LeaveType leaveType = ensureLeaveTypePresent(fkTypeRef);
                if (leaveType == null) {
                    continue;
                }

                int anneeEffective = remote.getAnnee() == null ? year : remote.getAnnee();
                Double effAvail = remote.resolveEffectiveQtyAvailable();
                Double initVal =
                        remote.getJoursInitiaux() != null ? remote.getJoursInitiaux() : effAvail;
                Double usedVal = remote.getJoursUtilises() != null ? remote.getJoursUtilises() : 0D;

                Long allocRemoteId =
                        normalizeAllocationRemoteId(remote, user.getDolibarrId(), fkTypeRef);

                sanitizeAllocationDates(remote, anneeEffective);
                LocalDate dDebut = remote.getDateDebut();
                LocalDate dFin = remote.getDateFin();

                EmployeeLeaveAllocation persisted = employeeLeaveAllocationRepository
                        .findByDolibarrAllocationId(allocRemoteId)
                        .orElse(null);
                if (persisted == null) {
                    persisted = employeeLeaveAllocationRepository
                            .findByEmployeeAndLeaveTypeAndAnneeAndActiveTrue(user, leaveType, anneeEffective)
                            .orElse(null);
                }

                if (persisted != null) {
                    persisted.setLeaveType(leaveType);
                    persisted.setDolibarrAllocationId(allocRemoteId);
                    persisted.setJoursInitiaux(initVal);
                    persisted.setJoursUtilises(usedVal);
                    persisted.setJoursDisponibles(effAvail);
                    persisted.setAnnee(anneeEffective);
                    persisted.setDateDebut(dDebut);
                    persisted.setDateFin(dFin);
                    persisted.setActive(remote.isActive());
                    persisted.setUpdatedAt(LocalDateTime.now());
                    employeeLeaveAllocationRepository.save(persisted);
                } else {
                    EmployeeLeaveAllocation newAllocation = EmployeeLeaveAllocation.builder()
                            .employee(user)
                            .leaveType(leaveType)
                            .dolibarrAllocationId(allocRemoteId)
                            .joursInitiaux(initVal)
                            .joursUtilises(usedVal)
                            .joursDisponibles(effAvail)
                            .annee(anneeEffective)
                            .dateDebut(dDebut)
                            .dateFin(dFin)
                            .active(remote.isActive())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    employeeLeaveAllocationRepository.save(newAllocation);
                }
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "Rafraîchissement allocations Dolibarr pour fk_user={}: {}",
                    user.getDolibarrId(),
                    ex.getMessage());
        }
    }

    private static Long normalizeAllocationRemoteId(
            DolibarrLeaveAllocationDto remote, Long fkUserDolibarr, Long fkType) {
        if (remote.getId() != null && remote.getId() > 0) {
            return remote.getId();
        }
        return jdbcSurrogateAllocationId(fkUserDolibarr, fkType);
    }

    private static long jdbcSurrogateAllocationId(long fkUser, long fkType) {
        return jdbcSurrogateAllocationId(Long.valueOf(fkUser), Long.valueOf(fkType));
    }

    private static long jdbcSurrogateAllocationId(Long fkUser, Long fkType) {
        long u = fkUser == null ? 0 : fkUser;
        long t = fkType == null ? 0 : fkType;
        return -(u * 1_000_000L + t);
    }

    /**
     * Données lues depuis {@code llx_holiday_users} (même cluster MySQL Dolibarr) — écrasent la réponse REST si les deux sont présentes.
     */
    private List<DolibarrLeaveAllocationDto> mergeJdbcFirstAllocationsForUser(Long fkUserDolibarr, int year) {
        List<DolibarrLeaveAllocationDto> api = fetchLeaveAllocationsFromDolibarrApi(fkUserDolibarr).stream()
                .filter(DolibarrLeaveAllocationDto::isActive)
                .filter(a -> fkUserDolibarr.equals(a.getEmployeeId()))
                .filter(a -> a.getAnnee() == null || a.getAnnee().equals(year))
                .toList();
        List<DolibarrLeaveAllocationDto> jdbc = fetchHolidayBalancesFromJdbc(fkUserDolibarr, year);

        Map<String, DolibarrLeaveAllocationDto> merged = new LinkedHashMap<>();
        for (DolibarrLeaveAllocationDto a : api) {
            merged.put(allocMergeKey(a, year), sanitizeAllocationDates(a, year));
        }
        for (DolibarrLeaveAllocationDto a : jdbc) {
            merged.put(allocMergeKey(a, year), sanitizeAllocationDates(a, year));
        }
        return new ArrayList<>(merged.values());
    }

    private static String allocMergeKey(DolibarrLeaveAllocationDto a, int defaultYear) {
        int y = a.getAnnee() == null ? defaultYear : a.getAnnee();
        return (a.getEmployeeId() != null ? a.getEmployeeId() : "") + "_" + a.getTypeCongeId() + "_" + y;
    }

    private static DolibarrLeaveAllocationDto sanitizeAllocationDates(DolibarrLeaveAllocationDto a, int defaultYear) {
        if (a.getAnnee() == null) {
            a.setAnnee(defaultYear);
        }
        if (a.getDateDebut() == null || a.getDateFin() == null) {
            a.setDateDebut(java.time.LocalDate.of(a.getAnnee(), 1, 1));
            a.setDateFin(java.time.LocalDate.of(a.getAnnee(), 12, 31));
        }
        return a;
    }

    private List<DolibarrLeaveAllocationDto> fetchHolidayBalancesFromJdbc(Long fkUserDolibarr, int defaultYear) {
        if (fkUserDolibarr == null) {
            return Collections.emptyList();
        }
        String tbl = qualifiedDolibarrTableName("holiday_users");
        try {
            String sql = "SELECT fk_user, fk_type, nb_holiday FROM `" + tbl + "` WHERE fk_user = ?";
            return dolibarrJdbcTemplate.query(
                    sql, ps -> ps.setLong(1, fkUserDolibarr), (rs, rowNum) -> mapJdbcHolidayUsersRow(rs, defaultYear));
        } catch (Exception ex) {
            log.debug("Lecture table Dolibarr {} ignorée : {}", tbl, ex.getMessage());
            return Collections.emptyList();
        }
    }

    private DolibarrLeaveAllocationDto mapJdbcHolidayUsersRow(ResultSet rs, int defaultYear)
            throws SQLException {
        long fku = rs.getLong("fk_user");
        long fkt = rs.getLong("fk_type");
        double nbHoliday = rs.getDouble("nb_holiday");

        DolibarrLeaveAllocationDto d = new DolibarrLeaveAllocationDto();
        d.setId(jdbcSurrogateAllocationId(fku, fkt));
        d.setEmployeeId(fku);
        d.setTypeCongeId(fkt);
        d.setAnnee(defaultYear);
        d.setJoursDisponibles(nbHoliday);
        d.setJoursInitiaux(nbHoliday);
        d.setJoursUtilises(0D);
        d.setDateDebut(LocalDate.of(defaultYear, 1, 1));
        d.setDateFin(LocalDate.of(defaultYear, 12, 31));
        d.setActive(1);
        return d;
    }

    private LeaveType ensureLeaveTypePresent(Long dolibarrTypeIdObj) {
        if (dolibarrTypeIdObj == null) {
            return null;
        }
        long dolibarrTypeId = dolibarrTypeIdObj.longValue();
        Optional<LeaveType> existing = leaveTypeRepository.findByDolibarrLeaveTypeId(dolibarrTypeId);
        if (existing.isPresent()) {
            return existing.get();
        }
        loadHolidayTypeFromJdbc(dolibarrTypeId).ifPresent(this::persistLeaveTypeFromJdbcRow);

        Optional<LeaveType> afterJdbc = leaveTypeRepository.findByDolibarrLeaveTypeId(dolibarrTypeId);
        if (afterJdbc.isPresent()) {
            return afterJdbc.get();
        }

        for (DolibarrLeaveTypeDto d : getLeaveTypesFromDolibarr()) {
            if (d.getId() != null && dolibarrTypeId == d.getId()) {
                return persistLeaveTypeFromDolibarrApi(d);
            }
        }
        log.warn("Type de congé Dolibarr rowid {} introuvable (c_holiday_types / REST).", dolibarrTypeId);
        return null;
    }

    private Optional<HolidayJdbcTypeRow> loadHolidayTypeFromJdbc(long rowid) {
        String tbl = qualifiedDolibarrTableName("c_holiday_types");
        try {
            String sql =
                    "SELECT rowid, code, label, delay, active FROM `" + tbl + "` WHERE rowid = ? LIMIT 1";
            List<HolidayJdbcTypeRow> rows = dolibarrJdbcTemplate.query(
                    sql,
                    ps -> ps.setLong(1, rowid),
                    (rs, rn) ->
                            new HolidayJdbcTypeRow(
                                    rs.getLong("rowid"),
                                    rs.getString("code"),
                                    rs.getString("label"),
                                    getNullableInt(rs, "delay"),
                                    rs.getObject("active") == null ? 1 : rs.getInt("active")));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        } catch (Exception ex) {
            log.debug("c_holiday_types rowid {} : {}", rowid, ex.getMessage());
            return Optional.empty();
        }
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private void persistLeaveTypeFromJdbcRow(HolidayJdbcTypeRow r) {
        LocalDateTime now = LocalDateTime.now();
        LeaveType lt = leaveTypeRepository
                .findByDolibarrLeaveTypeId(r.rowid())
                .orElseGet(
                        () ->
                                LeaveType.builder()
                                        .dolibarrLeaveTypeId(r.rowid())
                                        .code(sanitizeHolidayCode(r.code(), r.rowid()))
                                        .libelle(
                                                r.label() != null && !r.label().isBlank()
                                                        ? r.label()
                                                        : sanitizeHolidayCode(r.code(), r.rowid()))
                                        .description("")
                                        .active(r.activeFlag() == null || r.activeFlag() == 1)
                                        .requiresApproval(true)
                                        .delai(r.delay() != null ? r.delay().longValue() : 0L)
                                        .createdAt(now)
                                        .updatedAt(now)
                                        .build());
        lt.setCode(sanitizeHolidayCode(r.code(), r.rowid()));
        lt.setLibelle(
                r.label() != null && !r.label().isBlank() ? r.label() : lt.getCode());
        lt.setActive(r.activeFlag() == null || r.activeFlag() == 1);
        lt.setDelai(r.delay() != null ? r.delay().longValue() : lt.getDelai());
        lt.setUpdatedAt(now);
        leaveTypeRepository.save(lt);
    }

    private LeaveType persistLeaveTypeFromDolibarrApi(DolibarrLeaveTypeDto d) {
        if (d == null || d.getId() == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        long idVal = d.getId();
        LeaveType lt =
                leaveTypeRepository
                        .findByDolibarrLeaveTypeId(idVal)
                        .orElseGet(
                                () ->
                                        LeaveType.builder()
                                                .dolibarrLeaveTypeId(idVal)
                                                .code(sanitizeHolidayCode(d.getCode(), idVal))
                                                .libelle(
                                                        d.getLibelle() != null
                                                                ? d.getLibelle()
                                                                : sanitizeHolidayCode(d.getCode(), idVal))
                                                .description(
                                                        d.getDescription() != null ? d.getDescription() : "")
                                                .couleur(d.getCouleur())
                                                .active(d.isActive())
                                                .requiresApproval(d.requiresApproval())
                                                .delai(
                                                        d.getDelai() != null
                                                                ? d.getDelai().longValue()
                                                                : 0L)
                                                .createdAt(now)
                                                .updatedAt(now)
                                                .build());
        lt.setCode(sanitizeHolidayCode(d.getCode(), idVal));
        if (d.getLibelle() != null) {
            lt.setLibelle(d.getLibelle());
        }
        lt.setCouleur(d.getCouleur());
        lt.setActive(d.isActive());
        lt.setRequiresApproval(d.requiresApproval());
        if (d.getDelai() != null) {
            lt.setDelai(d.getDelai().longValue());
        }
        lt.setUpdatedAt(now);
        return leaveTypeRepository.save(lt);
    }

    private static String sanitizeHolidayCode(String rawCode, long rowidFallback) {
        if (rawCode != null && !rawCode.trim().isEmpty()) {
            String c = rawCode.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
            if (!c.isEmpty()) {
                return c.length() <= 63 ? c : c.substring(0, 63);
            }
        }
        return "DOL_" + rowidFallback;
    }

    /** Ligne catalogue Dolibarr {@code llx_c_holiday_types}. */
    private record HolidayJdbcTypeRow(long rowid, String code, String label, Integer delay, Integer activeFlag) {
    }

    /**
     * Met à jour (ou insère) {@code nb_holiday} dans la table Dolibarr {@code holiday_users}.
     * Utilise {@link #dolibarrJdbcTemplate} car la base Dolibarr est distincte de {@code conges_db}.
     */
    private boolean upsertHolidayUserBalanceJdbc(Long fkUserDolibarr, Long fkType, double qtyAvailable) {
        if (fkUserDolibarr == null || fkType == null) {
            return false;
        }
        String tbl = qualifiedDolibarrTableName("holiday_users");
        try {
            String sql = "UPDATE `" + tbl + "` SET nb_holiday = ? WHERE fk_user = ? AND fk_type = ?";
            int n = dolibarrJdbcTemplate.update(sql, qtyAvailable, fkUserDolibarr, fkType);
            if (n > 0) {
                return true;
            }
            try {
                String ins =
                        "INSERT INTO `"
                                + tbl
                                + "` (entity, fk_user, fk_type, nb_holiday) VALUES (1, ?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE nb_holiday = VALUES(nb_holiday)";
                dolibarrJdbcTemplate.update(ins, fkUserDolibarr, fkType, qtyAvailable);
                return true;
            } catch (Exception insertEx) {
                String insNoEntity =
                        "INSERT INTO `"
                                + tbl
                                + "` (fk_user, fk_type, nb_holiday) VALUES (?, ?, ?) "
                                + "ON DUPLICATE KEY UPDATE nb_holiday = VALUES(nb_holiday)";
                dolibarrJdbcTemplate.update(insNoEntity, fkUserDolibarr, fkType, qtyAvailable);
                return true;
            }
        } catch (Exception ex) {
            log.warn(
                    "UPSERT {} (nb_holiday) fk_user={}, fk_type={} : {}",
                    tbl,
                    fkUserDolibarr,
                    fkType,
                    ex.getMessage());
            return false;
        }
    }

    /**
     * Après correction RH sur les soldes : recopie la ligne locale vers Dolibarr (REST si {@code rowid} positif,
     * sinon table {@code holiday_users}) pour que la prochaine synchro / login relise cette valeur.
     */
    public boolean pushHrBalanceToDolibarr(EmployeeLeaveAllocation allocation) {
        if (!isDolibarrConfigured() || allocation == null) {
            return false;
        }
        UserEntity user = allocation.getEmployee();
        LeaveType lt = allocation.getLeaveType();
        if (user == null || lt == null || user.getDolibarrId() == null || lt.getDolibarrLeaveTypeId() == null) {
            log.warn(
                    "pushHrBalanceToDolibarr: liaison Dolibarr incomplète (allocation id={}, fk_user={}, fk_type={})",
                    allocation.getId(),
                    user != null ? user.getDolibarrId() : null,
                    lt != null ? lt.getDolibarrLeaveTypeId() : null);
            return false;
        }
        Long fkUser = user.getDolibarrId();
        Long fkType = lt.getDolibarrLeaveTypeId();
        Long remoteId = allocation.getDolibarrAllocationId();

        double avail = allocation.getJoursDisponibles() != null ? allocation.getJoursDisponibles() : 0d;
        double used = allocation.getJoursUtilises() != null ? allocation.getJoursUtilises() : 0d;
        double init = allocation.getJoursInitiaux() != null ? allocation.getJoursInitiaux() : used + avail;

        boolean ok;
        if (remoteId != null && remoteId > 0) {
            ok = putLeaveAllocationViaRest(remoteId, used, avail, init);
            if (!ok) {
                ok = upsertHolidayUserBalanceJdbc(fkUser, fkType, avail);
            }
        } else {
            ok = upsertHolidayUserBalanceJdbc(fkUser, fkType, avail);
        }

        Map<String, Object> payload =
                Map.of(
                        "fk_user",
                        fkUser,
                        "fk_type",
                        fkType,
                        "qty_available",
                        avail,
                        "qty_used",
                        used,
                        "qty_init",
                        init);

        if (ok) {
            dolibarrSyncLogService.logSuccess(
                    "LEAVE_ALLOCATION",
                    "HR_BALANCE_PUSH",
                    allocation.getId(),
                    remoteId != null && remoteId > 0 ? remoteId : null,
                    SyncDirection.OUTBOUND,
                    payload);
        } else {
            dolibarrSyncLogService.logFailure(
                    "LEAVE_ALLOCATION",
                    "HR_BALANCE_PUSH",
                    allocation.getId(),
                    remoteId,
                    SyncDirection.OUTBOUND,
                    "Échec écriture Dolibarr (REST ou base holiday_users)",
                    payload);
        }
        return ok;
    }

    private boolean putLeaveAllocationViaRest(Long allocationRemoteId, double used, double avail, double init) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("qty_used", used);
            payload.put("qty_available", avail);
            payload.put("qty_init", init);

            String url = dolibarrUrl + "/leaves/allocations/" + allocationRemoteId;
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, createHeaders());
            ResponseEntity<Map> response =
                    restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.warn(
                    "PUT allocation Dolibarr id={} : {} — repli JDBC holiday_users si possible.",
                    allocationRemoteId,
                    e.getMessage());
            return false;
        }
    }

    private boolean updateLeaveStatus(Long remoteLeaveId, String status, DemandeConge demande) {
        Map<String, Object> payload = Map.of("status", status);
        return callWithRetry("LEAVE_REQUEST", "UPDATE_STATUS", demande.getId(), remoteLeaveId, payload, () -> {
            String url = dolibarrUrl + "/leaves/" + remoteLeaveId;
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, createHeaders());
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
            return response.getStatusCode().is2xxSuccessful();
        });
    }

    private boolean consumeAllocationForApprovedLeave(DemandeConge demande) {
        if (demande.getUser().getDolibarrId() == null) {
            return false;
        }
        int year = demande.getDateDebut() == null ? java.time.Year.now().getValue() : demande.getDateDebut().getYear();
        refreshAllocationsForUser(demande.getUser(), year);

        Optional<EmployeeLeaveAllocation> allocationOpt = findAllocationForType(
                demande.getUser(),
                demande.getTypeConge(),
                year
        );
        if (allocationOpt.isEmpty()) {
            dolibarrSyncLogService.logFailure(
                    "LEAVE_ALLOCATION",
                    "CONSUME",
                    demande.getId(),
                    null,
                    SyncDirection.OUTBOUND,
                    "Allocation introuvable pour type " + demande.getTypeConge(),
                    null
            );
            return false;
        }

        EmployeeLeaveAllocation allocation = allocationOpt.get();
        double requested = demande.getNombreJoursExactOrInt();
        double nextUsed = (allocation.getJoursUtilises() == null ? 0D : allocation.getJoursUtilises()) + requested;
        double nextAvailable = (allocation.getJoursDisponibles() == null ? 0D : allocation.getJoursDisponibles()) - requested;
        if (nextAvailable < 0) {
            dolibarrSyncLogService.logFailure(
                    "LEAVE_ALLOCATION",
                    "CONSUME",
                    demande.getId(),
                    allocation.getDolibarrAllocationId(),
                    SyncDirection.OUTBOUND,
                    "Solde Dolibarr insuffisant",
                    Map.of("requestedDays", requested, "available", allocation.getJoursDisponibles())
            );
            return false;
        }

        Long allocationRemoteId = allocation.getDolibarrAllocationId();

        Map<String, Object> payload = new HashMap<>();
        payload.put("qty_used", nextUsed);
        payload.put("qty_available", nextAvailable);

        boolean updated;
        if (allocationRemoteId != null && allocationRemoteId < 0) {
            updated =
                    upsertHolidayUserBalanceJdbc(
                            demande.getUser().getDolibarrId(),
                            allocation.getLeaveType().getDolibarrLeaveTypeId(),
                            nextAvailable);
        } else {
            updated =
                    callWithRetry(
                            "LEAVE_ALLOCATION",
                            "CONSUME",
                            demande.getId(),
                            allocationRemoteId,
                            payload,
                            () -> {
                                String url =
                                        dolibarrUrl + "/leaves/allocations/" + allocationRemoteId;
                                HttpEntity<Map<String, Object>> entity =
                                        new HttpEntity<>(payload, createHeaders());
                                ResponseEntity<Map> response =
                                        restTemplate.exchange(url, HttpMethod.PUT, entity, Map.class);
                                return response.getStatusCode().is2xxSuccessful();
                            });
        }

        if (updated) {
            allocation.setJoursUtilises(nextUsed);
            allocation.setJoursDisponibles(nextAvailable);
            allocation.setUpdatedAt(LocalDateTime.now());
            employeeLeaveAllocationRepository.save(allocation);
        }

        return updated;
    }

    private Optional<EmployeeLeaveAllocation> findAllocationForType(UserEntity user, TypeConge typeConge, int year) {
        List<LeaveType> activeTypes = leaveTypeRepository.findByActiveTrue();
        List<LeaveType> candidates = activeTypes.stream()
                .filter(type -> mapTypeConge(typeConge, type))
                .toList();
        for (LeaveType leaveType : candidates) {
            Optional<EmployeeLeaveAllocation> allocation = employeeLeaveAllocationRepository
                    .findByEmployeeAndLeaveTypeAndAnneeAndActiveTrue(user, leaveType, year);
            if (allocation.isPresent()) {
                return allocation;
            }
        }
        return Optional.empty();
    }

    private boolean mapTypeConge(TypeConge typeConge, LeaveType leaveType) {
        String code = leaveType.getCode() == null ? "" : leaveType.getCode().toUpperCase(java.util.Locale.ROOT);
        String label = leaveType.getLibelle() == null ? "" : leaveType.getLibelle().toLowerCase(java.util.Locale.ROOT);
        return switch (typeConge) {
            case PAYE ->
                    code.contains("CONGES_PAYES")
                            || code.equals("CP")
                            || code.contains("ANNUAL")
                            || code.contains("VACATION")
                            || label.contains("pay")
                            || label.contains("annual")
                            || label.contains("vacan");
            case COURTE_DUREE -> code.contains("SHORT") || code.contains("COURTE") || label.contains("courte");
            case RTT -> code.contains("RTT") || label.contains("rtt");
            case MALADIE ->
                    code.contains("MALADIE")
                            || code.contains("SICK")
                            || label.contains("malad")
                            || label.contains("sick");
            case SANS_SOLDE ->
                    code.contains("SANS_SOLDE")
                            || code.contains("NOPAID")
                            || code.contains("UNPAID")
                            || label.contains("sans solde")
                            || label.contains("unpaid");
            case EXCEPTIONNEL -> false;
        };
    }

    private boolean callWithRetry(
            String entityType,
            String operation,
            Long localEntityId,
            Long remoteEntityId,
            Object payload,
            java.util.function.Supplier<Boolean> call
    ) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= OUTBOUND_MAX_RETRIES; attempt++) {
            try {
                boolean ok = Boolean.TRUE.equals(call.get());
                if (ok) {
                    dolibarrSyncLogService.logSuccess(
                            entityType,
                            operation,
                            localEntityId,
                            remoteEntityId,
                            SyncDirection.OUTBOUND,
                            payload
                    );
                    return true;
                }
            } catch (Exception ex) {
                lastError = ex;
                log.warn("Dolibarr outbound {} tentative {}/{} échouée: {}",
                        operation, attempt, OUTBOUND_MAX_RETRIES, ex.getMessage());
            }
            try {
                Thread.sleep(300L * attempt);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        dolibarrSyncLogService.logFailure(
                entityType,
                operation,
                localEntityId,
                remoteEntityId,
                SyncDirection.OUTBOUND,
                lastError == null ? "Échec API Dolibarr" : lastError.getMessage(),
                payload
        );
        return false;
    }

    /** TN | FR | MA si reconnu après normalisation DOM ; sinon TN. */
    public String normalizeToSupportedHrPays(String isoGuess) {
        if (isoGuess == null || isoGuess.isBlank()) {
            return "TN";
        }
        String u = isoGuess.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) {
            return "TN";
        }
        
        // 1. Outre-mer français → FR
        u = mapFrenchOverseasToMetro(u);
        if ("TN".equals(u) || "FR".equals(u) || "MA".equals(u)) {
            return u;
        }
        
        // 2. Chercher par mots-clés avant de rejeter
        String fromLabel = iso2FromLabel(isoGuess);
        if (fromLabel != null && !fromLabel.isBlank()) {
            return mapFrenchOverseasToMetro(fromLabel);
        }
        
        // 3. Défaut: Tunisie
        return "TN";
    }

    /** À la connexion et pour la synchro employés depuis Dolibarr. */
    public String resolveSupportedHrCountryIso2(Long dolibarrRowId, DolibarrEmployeeDto dto) {
        return normalizeToSupportedHrPays(resolveCountryCode(dolibarrRowId, dto));
    }

    /**
     * Priorité jointure utilisateur Dolibarr + table pays puis champs pays de la réponse API.
     *
     * @return Alpha-2 (DOM français normalisées en FR métropole métier), ou {@code null}
     */
    public String resolveCountryCode(Long dolibarrUserRowId, DolibarrEmployeeDto dto) {
        String fromSql = iso2FromJoinRowJdbc(dolibarrUserRowId);
        if (fromSql != null && !fromSql.isBlank()) {
            return mapFrenchOverseasToMetro(fromSql.trim().toUpperCase(Locale.ROOT));
        }
        if (dto != null && dto.getCountryCode() != null && !dto.getCountryCode().isBlank()) {
            return iso2FromMixedApi(dto.getCountryCode().trim());
        }
        return null;
    }

    private String iso2FromJoinRowJdbc(Long userRowId) {
        if (userRowId == null) {
            return null;
        }
        String tblUser = qualifiedDolibarrTableName("user");
        String tblCountry = qualifiedDolibarrTableName("c_country");
        String sql = "SELECT c.code AS c_code, c.code_iso AS c_code_iso, c.label AS c_label "
                + "FROM `" + tblUser + "` u "
                + "LEFT JOIN `" + tblCountry + "` c ON c.rowid = u.fk_country "
                + "WHERE u.rowid = ? LIMIT 1";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, userRowId);
            if (rows.isEmpty()) {
                return null;
            }
            return rowToIso2Guess(rows.get(0));
        } catch (Exception ex) {
            log.debug("Lecture fk_country Dolibarr (SQL): {}", ex.getMessage());
            return null;
        }
    }

    private String qualifiedDolibarrTableName(String logicalSuffixSansLlPrefix) {
        String prefix = dolibarrTablePrefix == null ? "llx_" : dolibarrTablePrefix.trim().replace("`", "");
        prefix = prefix.replaceAll("[^a-zA-Z0-9_]", "");
        if (prefix.isEmpty()) {
            prefix = "llx";
        }
        if (!prefix.endsWith("_")) {
            prefix += "_";
        }
        String base = logicalSuffixSansLlPrefix == null ? ""
                : logicalSuffixSansLlPrefix.replaceFirst("(?i)^llx_", "").replaceAll("[^a-zA-Z0-9_]", "");
        return prefix + base;
    }

    private static String rowToIso2Guess(Map<String, Object> row) {
        String codeIso = toTrimmedString(row.get("c_code_iso"));
        String code = toTrimmedString(row.get("c_code"));
        String raw = firstNonBlank(codeIso, code);
        String guessed = iso2FromMixedApi(raw);
        if (guessed == null || guessed.isBlank()) {
            guessed = iso2FromLabel(toTrimmedString(row.get("c_label")));
        }
        return guessed;
    }

    private static String toTrimmedString(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String iso2FromMixedApi(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        
        // Codes numériques (250=FR, 788=TN, 504=MA)
        if (s.matches("\\d{1,3}")) {
            try {
                int n = Integer.parseInt(s);
                String r = NUMERIC_ISO_TO_ALPHA2.get(n);
                return r != null ? mapFrenchOverseasToMetro(r) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        
        // Codes ISO2 2 caractères (TN, FR, MA, etc.)
        if (s.length() == 2 && s.matches("[A-Z]{2}")) {
            return mapFrenchOverseasToMetro(s);
        }
        
        // Codes ISO3 3 caractères (FRA, TUN, MAR, etc.)
        if (s.length() == 3) {
            String r = ISO3166_ALPHA3_TO2.get(s);
            return r != null ? mapFrenchOverseasToMetro(r) : null;
        }
        
        // Noms complets ou variantes (TUNISIA, FRANCE, MAROC, etc.)
        return iso2FromLabel(raw);
    }

    private static String iso2FromLabel(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String t = label.toLowerCase(Locale.ROOT);
        
        // Recherche par mots-clés (ordre : TN, FR, MA pour cohérence backend)
        if (t.contains("tunis")) {
            return "TN";
        }
        if (t.contains("franc") || t.contains("france")) {
            return "FR";
        }
        if (t.contains("maroc") || t.contains("morocco")) {
            return "MA";
        }
        
        // Aucune correspondance trouvée
        return null;
    }

    /** Outre-mer français → même socle quotas que métropole. */
    private static String mapFrenchOverseasToMetro(String iso2) {
        if (iso2 == null || iso2.isBlank()) {
            return iso2;
        }
        String u = iso2.trim().toUpperCase(Locale.ROOT);
        if (FR_OVERSEAS_ISO2.contains(u)) {
            return "FR";
        }
        return u;
    }

    /**
     * Vérifie si Dolibarr est configuré (URL + clé API).
     */
    private boolean isDolibarrConfigured() {
        return dolibarrUrl != null && !dolibarrUrl.isEmpty() &&
               dolibarrApiKey != null && !dolibarrApiKey.isEmpty();
    }

    /** Exposé aux services métier (soldes et validation alignés Dolibarr). */
    public boolean isDolibarrConnectionConfigured() {
        return isDolibarrConfigured();
    }

    /**
     * Indique si l’utilisateur est lié à Dolibarr (API configurée) : les <strong>montants</strong> de solde utilisés
     * pour contrôler les demandes viennent alors des {@linkplain com.example.conges.entity.EmployeeLeaveAllocation
     * allocations locales} alimentées par la synchro (reflétant ce qui est stocké dans Dolibarr), et non d’un
     * « calcul pays » interne pour le même flux. Dolibarr ne calcule pas les règles TN/MA/FR : il enregistre et
     * renvoie des quantités ; la consommation définitive peut être répercutée vers Dolibarr à l’approbation.
     */
    public boolean isLeaveBalanceFromDolibarr(UserEntity user) {
        return user != null && user.getDolibarrId() != null && isDolibarrConfigured();
    }

    /**
     * Crée les headers HTTP avec la clé API Dolibarr
     */
    private HttpHeaders createHeaders() {
        return createHeaders(MediaType.APPLICATION_JSON);
    }

    private HttpHeaders createHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("DOLAPIKEY", dolibarrApiKey);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(contentType);
        return headers;
    }

    /**
     * Récupère le statut de la configuration Dolibarr
     */
    public String getDolibarrStatus() {
        if (!isDolibarrConfigured()) {
            return "❌ Non configuré (manque URL ou clé API)";
        }

        boolean connected = testDolibarrConnection();
        if (connected) {
            return "✅ Connecté à Dolibarr";
        } else {
            return "❌ Erreur de connexion à Dolibarr";
        }
    }
}
