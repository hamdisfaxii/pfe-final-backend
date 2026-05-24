package com.example.conges.service;

import com.example.conges.dto.AuthResponse;
import com.example.conges.dto.LoginRequest;
import com.example.conges.dto.dolibarr.DolibarrEmployeeDto;
import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final DolibarrService dolibarrService;
    private final DolibarrBackgroundSyncFacade dolibarrBackgroundSyncFacade;

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.getEmail() == null ? null : request.getEmail().trim();
        String password = request.getPassword();
        log.info("🔐 Tentative login email={}", email);

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email/mot de passe obligatoires");
        }

        // 1. Authentification Dolibarr : DB directe (la plus fiable) + fallback REST API
        DolibarrEmployeeDto dolibarrUser = dolibarrService.authenticateViaDolibarrDb(email, password);
        if (dolibarrUser == null) {
            dolibarrUser = dolibarrService.authenticateUserViaApi(email, password);
        }
        if (dolibarrUser != null) {
            AuthResponse response = handleDolibarrLogin(dolibarrUser, email);
            scheduleFullDolibarrSyncAfterCommitIfConfigured();
            return response;
        }

        // 2. Fallback : authentification locale (mode standalone sans Dolibarr)
        UserEntity localUser = userRepository.findByEmail(email).orElse(null);
        if (localUser != null && localUser.getPasswordHash() != null
                && PASSWORD_ENCODER.matches(password, localUser.getPasswordHash())) {
            log.info("✅ Connexion locale OK userId={}, email={}", localUser.getId(), localUser.getEmail());
            String token = jwtService.generateToken(localUser);
            return AuthResponse.builder()
                    .token(token)
                    .type("Bearer")
                    .user(AuthResponse.UserInfo.builder()
                            .id(localUser.getId())
                            .email(localUser.getEmail())
                            .nom(localUser.getNom())
                            .prenom(localUser.getPrenom())
                            .role(localUser.getRole())
                            .pays(localUser.getPays())
                            .departement(localUser.getDepartement())
                            .build())
                    .build();
        }

        // 3. L'email existe dans Dolibarr mais le login name est différent → réessayer avec le vrai login
        if (dolibarrService.isDolibarrConnectionConfigured()) {
            DolibarrEmployeeDto existingUser = dolibarrService.findEmployeeByCredential(email);
            if (existingUser != null) {
                String doliLogin = existingUser.getLogin();
                if (doliLogin != null && !doliLogin.isBlank() && !doliLogin.equalsIgnoreCase(email)) {
                    log.info("🔄 Retry auth Dolibarr avec login name={} pour email={}", doliLogin, email);
                    DolibarrEmployeeDto retried = dolibarrService.authenticateUserViaApi(doliLogin, password);
                    if (retried != null) {
                        AuthResponse response = handleDolibarrLogin(retried, doliLogin);
                        scheduleFullDolibarrSyncAfterCommitIfConfigured();
                        return response;
                    }
                }
                // Fallback : Basic Auth — le endpoint /login Dolibarr peut être désactivé ou non fiable
                String loginForBasic = (doliLogin != null && !doliLogin.isBlank()) ? doliLogin : email;
                log.info("🔄 Tentative Basic Auth Dolibarr pour login={}", loginForBasic);
                DolibarrEmployeeDto viaBasic = dolibarrService.authenticateViaBasicAuth(loginForBasic, password);
                if (viaBasic != null) {
                    AuthResponse response = handleDolibarrLogin(viaBasic, loginForBasic);
                    scheduleFullDolibarrSyncAfterCommitIfConfigured();
                    return response;
                }
                log.warn("⚠️ Mot de passe incorrect pour email={}.", email);
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Mot de passe incorrect ou identifiant invalide.");
            }

            // Aucun utilisateur trouvé via admin API : tenter Basic Auth directement avec l'email
            // (cas où clé admin invalide/expirée mais credentials utilisateur corrects)
            log.info("🔄 Basic Auth direct pour email={} (admin API indisponible)", email);
            DolibarrEmployeeDto viaBasicDirect = dolibarrService.authenticateViaBasicAuth(email, password);
            if (viaBasicDirect != null) {
                AuthResponse response = handleDolibarrLogin(viaBasicDirect, email);
                scheduleFullDolibarrSyncAfterCommitIfConfigured();
                return response;
            }
        }

        log.warn("⚠️ Employé introuvable pour email={}. Connexion refusée.", email);
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Email ou mot de passe incorrect.");
    }

    private AuthResponse handleDolibarrLogin(DolibarrEmployeeDto dolibarrUser, String credential) {
        // L'email réel vient de Dolibarr ; le credential entré peut être un login name ou un email.
        String dolibarrEmail = dolibarrUser.getEmail() != null && !dolibarrUser.getEmail().isBlank()
                ? dolibarrUser.getEmail().trim().toLowerCase()
                : credential.trim().toLowerCase();

        // Lookup : par dolibarrId d'abord, puis par email Dolibarr, puis par credential saisi
        UserEntity user = userRepository.findByDolibarrId(dolibarrUser.getId())
                .orElseGet(() -> {
                    UserEntity byDoliEmail = userRepository.findByEmail(dolibarrEmail).orElse(null);
                    if (byDoliEmail != null) return byDoliEmail;
                    if (!dolibarrEmail.equalsIgnoreCase(credential)) {
                        return userRepository.findByEmail(credential.trim().toLowerCase()).orElse(null);
                    }
                    return null;
                });

        Role role = resolveRole(user, dolibarrUser, credential);
        String nom = dolibarrUser.getLastName();
        String prenom = dolibarrUser.getFirstName();
        String paysRH = dolibarrService.resolveSupportedHrCountryIso2(
                dolibarrUser.getId(), dolibarrUser);

        if (user == null) {
            user = UserEntity.builder()
                    .dolibarrId(dolibarrUser.getId())
                    .email(dolibarrEmail)
                    .nom(nom)
                    .prenom(prenom)
                    .role(role)
                    .pays(paysRH)
                    .build();
        } else {
            user.setDolibarrId(dolibarrUser.getId());
            user.setEmail(dolibarrEmail);
            user.setNom(nom);
            user.setPrenom(prenom);
            user.setRole(role);
            user.setPays(paysRH);
        }
        user = userRepository.save(user);

        String token = jwtService.generateToken(user);
        log.info("✅ Connexion Dolibarr OK userId={}, email={}, role={}", user.getId(), user.getEmail(), user.getRole());

        return AuthResponse.builder()
                .token(token)
                .type("Bearer")
                .user(AuthResponse.UserInfo.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .nom(user.getNom())
                        .prenom(user.getPrenom())
                        .role(user.getRole())
                        .pays(user.getPays())
                        .departement(user.getDepartement())
                        .build())
                .build();
    }

    private Role resolveRole(UserEntity existingUser, DolibarrEmployeeDto dolibarrUser, String credential) {
        // RH et MANAGER sont assignés manuellement en local — toujours préserver
        if (existingUser != null) {
            Role r = existingUser.getRole();
            if (r == Role.RH || r == Role.MANAGER) {
                return r;
            }
        }

        // Admin : se base uniquement sur le flag Dolibarr (évite de conserver un ADMIN corrompu)
        if (dolibarrUser != null && dolibarrUser.isAdminLike()) {
            return Role.ADMIN;
        }

        // Heuristique login Dolibarr (ex. compte nommé "admin")
        String login = dolibarrUser == null ? "" : String.valueOf(dolibarrUser.getLogin()).toLowerCase();
        if (login.contains("admin")) {
            return Role.ADMIN;
        }

        return Role.EMPLOYE;
    }

    /**
     * Une fois la transaction login validée : resynchroniser employés / types / féries / allocations
     * depuis Dolibarr (cohérence avec donnée métier après chaque connexion).
     */
    private void scheduleFullDolibarrSyncAfterCommitIfConfigured() {
        if (!dolibarrService.isDolibarrConnectionConfigured()) {
            return;
        }
        Runnable run = () -> dolibarrBackgroundSyncFacade.triggerFullSyncAfterLogin();
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            run.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        run.run();
                    }
                });
    }
}
