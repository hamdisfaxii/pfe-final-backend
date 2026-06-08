package com.example.conges.config;

import com.example.conges.entity.EmployeeLeaveAllocation;
import com.example.conges.entity.LeaveType;
import com.example.conges.entity.Role;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.EmployeeLeaveAllocationRepository;
import com.example.conges.repository.LeaveTypeRepository;
import com.example.conges.repository.UserRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initialise les données par défaut au démarrage :
 * - Utilisateurs RH/employé de test avec mots de passe locaux
 * - Types de congés standards
 * - Allocations par défaut pour les utilisateurs existants sans allocation
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final LeaveTypeRepository leaveTypeRepository;
    private final EmployeeLeaveAllocationRepository allocationRepository;

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    // Types de congés par défaut avec leurs IDs synthétiques locaux
    private static final List<LeaveTypeSpec> DEFAULT_LEAVE_TYPES = List.of(
            new LeaveTypeSpec(-1L, "CP",  "Congés Payés",       TypeConge.PAYE),
            new LeaveTypeSpec(-2L, "RTT", "RTT",                TypeConge.COURTE_DUREE),
            new LeaveTypeSpec(-3L, "MAL","Congé Maladie",       TypeConge.MALADIE),
            new LeaveTypeSpec(-4L, "SS", "Congé Sans Solde",    TypeConge.SANS_SOLDE)
    );

    // Quota annuel par type et par pays
    private static final Map<String, Map<TypeConge, Double>> COUNTRY_QUOTAS = Map.of(
            "TN", Map.of(TypeConge.PAYE, 22.0, TypeConge.MALADIE, 7.0, TypeConge.SANS_SOLDE, 0.0, TypeConge.COURTE_DUREE, 0.0),
            "FR", Map.of(TypeConge.PAYE, 25.0, TypeConge.COURTE_DUREE, 9.0, TypeConge.MALADIE, 0.0, TypeConge.SANS_SOLDE, 0.0),
            "MA", Map.of(TypeConge.PAYE, 18.0, TypeConge.MALADIE, 7.0, TypeConge.SANS_SOLDE, 0.0, TypeConge.COURTE_DUREE, 0.0)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedLeaveTypes();
        seedDefaultUsers();
        seedMissingAllocations();
        log.info("✅ DataInitializer terminé");
    }

    private void seedLeaveTypes() {
        for (LeaveTypeSpec spec : DEFAULT_LEAVE_TYPES) {
            boolean exists = leaveTypeRepository.findByCode(spec.code()).isPresent();
            if (!exists) {
                LocalDateTime now = LocalDateTime.now();
                LeaveType lt = LeaveType.builder()
                        .dolibarrLeaveTypeId(spec.dolibarrId())
                        .code(spec.code())
                        .libelle(spec.libelle())
                        .description("")
                        .active(true)
                        .requiresApproval(true)
                        .delai(0L)
                        .syncStatus(1)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                leaveTypeRepository.save(lt);
                log.info("  → Type de congé créé : {}", spec.code());
            }
        }
    }

    private void seedDefaultUsers() {
        createUserIfAbsent("rh@conges.local",      "Martin",  "Marie",   Role.RH,      "TN", "rh123");
        createUserIfAbsent("employe@conges.local", "Dupont",  "Jean",    Role.EMPLOYE, "TN", "employe123");
        createUserIfAbsent("employe.fr@conges.local","Martin","Sophie",  Role.EMPLOYE, "FR", "employe123");
        createUserIfAbsent("employe.ma@conges.local","Benali", "Youssef",Role.EMPLOYE, "MA", "employe123");
    }

    private void createUserIfAbsent(String email, String nom, String prenom, Role role, String pays, String rawPassword) {
        if (userRepository.findByEmail(email).isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            UserEntity u = UserEntity.builder()
                    .email(email)
                    .nom(nom)
                    .prenom(prenom)
                    .role(role)
                    .pays(pays)
                    .passwordHash(ENCODER.encode(rawPassword))
                    .contractActive(true)
                    .contractType("CDI")
                    .annualWorkDays(218)
                    .weeklyHours(new java.math.BigDecimal("40.0"))
                    .hireDate(LocalDate.of(2023, 1, 1))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            userRepository.save(u);
            log.info("  → Utilisateur créé : {} ({})", email, role);
        }
    }

    private void seedMissingAllocations() {
        int year = Year.now().getValue();
        List<UserEntity> users = userRepository.findAll();

        for (UserEntity user : users) {
            String pays = user.getPays() == null ? "TN" : user.getPays().trim().toUpperCase();
            if (!COUNTRY_QUOTAS.containsKey(pays)) {
                pays = "TN";
            }
            Map<TypeConge, Double> quotas = COUNTRY_QUOTAS.get(pays);

            List<EmployeeLeaveAllocation> existing = allocationRepository.findByEmployeeAndAnnee(user, year);
            if (!existing.isEmpty()) {
                continue;
            }

            for (LeaveTypeSpec spec : DEFAULT_LEAVE_TYPES) {
                LeaveType lt = leaveTypeRepository.findByCode(spec.code()).orElse(null);
                if (lt == null) continue;

                double quota = quotas.getOrDefault(spec.typeConge(), 0.0);
                LocalDateTime now = LocalDateTime.now();
                EmployeeLeaveAllocation alloc = EmployeeLeaveAllocation.builder()
                        .employee(user)
                        .leaveType(lt)
                        .dolibarrAllocationId(null)
                        .joursInitiaux(quota)
                        .joursUtilises(0.0)
                        .joursDisponibles(quota)
                        .annee(year)
                        .dateDebut(LocalDate.of(year, 1, 1))
                        .dateFin(LocalDate.of(year, 12, 31))
                        .active(true)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                allocationRepository.save(alloc);
            }
            log.info("  → Allocations créées pour {} (pays={})", user.getEmail(), pays);
        }
    }

    private record LeaveTypeSpec(Long dolibarrId, String code, String libelle, TypeConge typeConge) {}
}
