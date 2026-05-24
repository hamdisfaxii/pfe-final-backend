package com.example.conges.service;

import com.example.conges.dto.hr.HrLeaveBalanceDtos;
import com.example.conges.entity.EmployeeLeaveAllocation;
import com.example.conges.entity.LeaveType;
import com.example.conges.entity.Role;
import com.example.conges.entity.TypeConge;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.EmployeeLeaveAllocationRepository;
import com.example.conges.repository.UserRepository;
import java.time.LocalDate;
import java.time.Year;
import com.example.conges.entity.EmployeeFranceRttBalance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Grille RH des soldes par type métier et propagation manuelle vers Dolibarr lorsque configuré.
 * Les montants affichés viennent des allocations locales (synchro) ; la correction RH réécrit vers Dolibarr pour aligner le stockage ERP.
 */
@Service
@RequiredArgsConstructor
public class HrLeaveBalanceService {

    private final UserRepository userRepository;
    private final EmployeeLeaveAllocationRepository employeeLeaveAllocationRepository;
    private final CountryPolicyService countryPolicyService;
    private final DolibarrService dolibarrService;
    private final FranceRttLedgerService franceRttLedgerService;

    @Transactional(readOnly = true)
    public HrLeaveBalanceDtos.PageResponse listBalances(
            String q,
            String country,
            Role role,
            Integer year,
            int page,
            int size
    ) {
        int y = year != null ? year : Year.now().getValue();
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 100));
        Page<UserEntity> users = userRepository.searchUsers(normalizeNullable(q), normalizeNullable(country), role, pageable);

        List<Long> userIds = users.getContent().stream().map(UserEntity::getId).filter(Objects::nonNull).toList();
        Map<Long, List<EmployeeLeaveAllocation>> allocByUser = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (EmployeeLeaveAllocation a : employeeLeaveAllocationRepository.findByEmployeeIdsAndAnnee(userIds, y)) {
                Long uid = a.getEmployee() != null ? a.getEmployee().getId() : null;
                if (uid == null) continue;
                allocByUser.computeIfAbsent(uid, __ -> new ArrayList<>()).add(a);
            }
        }

        List<HrLeaveBalanceDtos.BalanceRow> rows = users.getContent().stream()
                .map(u -> toRow(u, y, allocByUser.getOrDefault(u.getId(), List.of())))
                .toList();

        return HrLeaveBalanceDtos.PageResponse.builder()
                .items(rows)
                .page(users.getNumber())
                .size(users.getSize())
                .total(users.getTotalElements())
                .totalPages(users.getTotalPages())
                .build();
    }

    @Transactional
    public void applyUpdates(List<HrLeaveBalanceDtos.UpdateRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        for (HrLeaveBalanceDtos.UpdateRequest req : requests) {
            applyUpdate(req);
        }
    }

    private void applyUpdate(HrLeaveBalanceDtos.UpdateRequest req) {
        if (req == null) return;
        Long userId = req.getUserId();
        Integer year = req.getYear();
        if (userId == null || year == null) {
            throw new IllegalArgumentException("userId et year sont obligatoires");
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("Utilisateur introuvable"));

        List<EmployeeLeaveAllocation> allocations =
                employeeLeaveAllocationRepository.findByEmployeeAndAnnee(user, year);

        Map<TypeConge, EmployeeLeaveAllocation> byType = indexByType(allocations);

        for (HrLeaveBalanceDtos.UpdateLine line : Optional.ofNullable(req.getUpdates()).orElse(List.of())) {
            TypeConge t = line.getTypeConge();
            if (t == null) continue;
            if (t == TypeConge.COURTE_DUREE && !isRttApplicable(user)) {
                throw new IllegalArgumentException("RTT non applicable pour cet employé (pays TN/MA).");
            }
            // RTT France : override direct sur le ledger, pas via EmployeeLeaveAllocation.
            if (t == TypeConge.COURTE_DUREE && franceRttLedgerService.isFranceRttTrackedForUi(user)) {
                double remaining = line.getRemaining() == null ? 0 : line.getRemaining();
                if (remaining < 0) {
                    throw new IllegalArgumentException("Solde négatif interdit.");
                }
                franceRttLedgerService.setRttRemainingOverride(user, year, remaining);
                continue;
            }
            double remaining = line.getRemaining() == null ? 0 : line.getRemaining();
            if (remaining < 0) {
                throw new IllegalArgumentException("Solde négatif interdit.");
            }

            EmployeeLeaveAllocation a = byType.get(t);
            if (a == null) {
                throw new IllegalArgumentException(
                        "Allocation introuvable pour " + t + ". Synchronisez Dolibarr et réessayez.");
            }

            double used = safe0(a.getJoursUtilises());
            a.setJoursDisponibles(remaining);
            a.setJoursInitiaux(Math.max(0, used + remaining));
            a.setUpdatedAt(java.time.LocalDateTime.now());
            employeeLeaveAllocationRepository.save(a);

            if (dolibarrService.isDolibarrConnectionConfigured()) {
                dolibarrService.pushHrBalanceToDolibarr(a);
            }
        }
    }

    private HrLeaveBalanceDtos.BalanceRow toRow(UserEntity u, int year, List<EmployeeLeaveAllocation> allocations) {
        Map<TypeConge, EmployeeLeaveAllocation> byType = indexByType(allocations);

        List<HrLeaveBalanceDtos.BalanceLine> lines = new ArrayList<>();
        for (TypeConge t : TypeConge.values()) {
            // RTT uniquement pour la France — TN et MA n'ont pas de ligne COURTE_DUREE.
            if (t == TypeConge.COURTE_DUREE && !isRttApplicable(u)) {
                continue;
            }
            // RTT France : provient du ledger, pas d'une allocation Dolibarr.
            if (t == TypeConge.COURTE_DUREE && franceRttLedgerService.isFranceRttTrackedForUi(u)) {
                Map<String, Object> snap = franceRttLedgerService.ledgerSnapshot(u, year, LocalDate.now());
                double total, used, remaining;
                if (!snap.isEmpty()) {
                    total = asDouble(snap.get("rtt_total"));
                    used = asDouble(snap.get("rtt_used"));
                    remaining = asDouble(snap.get("rtt_remaining"));
                } else {
                    EmployeeFranceRttBalance bal = franceRttLedgerService.getPersistedBalance(u, year);
                    if (bal == null) continue;
                    total = bal.getRttTotal() != null ? bal.getRttTotal().doubleValue() : 0;
                    used = bal.getRttUsed() != null ? bal.getRttUsed().doubleValue() : 0;
                    remaining = bal.getRttRemaining() != null ? bal.getRttRemaining().doubleValue() : 0;
                }
                lines.add(HrLeaveBalanceDtos.BalanceLine.builder()
                        .typeConge(t)
                        .total(safe0(total))
                        .used(safe0(used))
                        .remaining(safe0(remaining))
                        .readOnly(false)
                        .message("")
                        .build());
                continue;
            }
            EmployeeLeaveAllocation a = byType.get(t);
            if (a == null) {
                continue; // pas d'allocation pour ce type → cellule vide (—) côté RH
            }
            HrLeaveBalanceDtos.BalanceLine line = HrLeaveBalanceDtos.BalanceLine.builder()
                    .typeConge(t)
                    .total(safe0(a.getJoursInitiaux()))
                    .used(safe0(a.getJoursUtilises()))
                    .remaining(safe0(a.getJoursDisponibles()))
                    .readOnly(false)
                    .message("")
                    .build();
            lines.add(line);
        }

        return HrLeaveBalanceDtos.BalanceRow.builder()
                .user(HrLeaveBalanceDtos.UserInfo.builder()
                        .id(u.getId())
                        .email(u.getEmail())
                        .nom(u.getNom())
                        .prenom(u.getPrenom())
                        .pays(u.getPays())
                        .departement(u.getDepartement())
                        .role(u.getRole())
                        .build())
                .year(year)
                .balances(lines)
                .build();
    }

    private boolean isRttApplicable(UserEntity user) {
        if (user == null) return false;
        return countryPolicyService.isRttEnabledForCountry(user.getPays());
    }

    /**
     * Même grille que l’écran RH Soldes (codes/libellés Dolibarr + repli « plus gros solde » pour CP).
     * À utiliser aussi pour {@code /conge/solde} afin d’éviter 25 j. côté RH et 0 côté employé.
     */
    public Map<TypeConge, EmployeeLeaveAllocation> mapAllocationsToBusinessTypes(
            List<EmployeeLeaveAllocation> allocations) {
        return indexByType(allocations);
    }

    private Map<TypeConge, EmployeeLeaveAllocation> indexByType(List<EmployeeLeaveAllocation> allocations) {
        Map<TypeConge, EmployeeLeaveAllocation> m = new EnumMap<>(TypeConge.class);
        if (allocations == null || allocations.isEmpty()) {
            for (TypeConge t : TypeConge.values()) {
                m.put(t, null);
            }
            return m;
        }
        // Types plus spécifiques en premier pour éviter qu’un seul type Dolibarr “mange” toutes les lignes.
        List<TypeConge> order = List.of(
                TypeConge.MALADIE, TypeConge.COURTE_DUREE, TypeConge.SANS_SOLDE, TypeConge.PAYE);
        Set<EmployeeLeaveAllocation> used = new HashSet<>();
        for (TypeConge t : order) {
            EmployeeLeaveAllocation found = findAllocationForType(t, allocations);
            if (found != null && !used.contains(found)) {
                m.put(t, found);
                used.add(found);
            } else {
                m.put(t, null);
            }
        }
        // Fallback CP : si aucun libellé ne matche, on affiche le plus gros solde restant non déjà affecté.
        if (m.get(TypeConge.PAYE) == null) {
            allocations.stream()
                    .filter(a -> !used.contains(a))
                    .filter(a -> a.getActive() == null || Boolean.TRUE.equals(a.getActive()))
                    .max(Comparator.comparingDouble(a -> safe0(a.getJoursDisponibles())))
                    .ifPresent(pick -> m.put(TypeConge.PAYE, pick));
        }
        return m;
    }

    /**
     * Reproduit l'idée de CongeService.findAllocationByType sans déclencher de sync réseau.
     * On matche à la fois le code Dolibarr et le libellé.
     */
    private EmployeeLeaveAllocation findAllocationForType(TypeConge type, List<EmployeeLeaveAllocation> allocations) {
        if (allocations == null || allocations.isEmpty() || type == null) return null;
        List<String> candidates = candidatesForType(type);
        for (EmployeeLeaveAllocation a : allocations) {
            LeaveType lt = a.getLeaveType();
            String code = lt != null ? normalize(lt.getCode()) : "";
            String lib = lt != null ? normalize(lt.getLibelle()) : "";
            for (String c : candidates) {
                if (!c.isEmpty() && (code.contains(c) || lib.contains(c))) {
                    return a;
                }
            }
        }
        return null;
    }

    private List<String> candidatesForType(TypeConge type) {
        // Libellés/codes variables selon pays, langue Dolibarr et dictionnaire local.
        List<String> base = switch (type) {
            case PAYE -> List.of(
                    "paye", "paid", "conge_paye", "conges_payes", "cp", "vacation", "annual", "annuel",
                    "holiday", "leave", "conge", "conges", "absence", "time off", "pto", "paid leave");
            case MALADIE -> List.of(
                    "maladie", "sick", "medical", "med", "incapacity", "incapacite", "incapacité",
                    "arret", "arrêt", "arret maladie", "arrêt maladie", "illness");
            case SANS_SOLDE -> List.of(
                    "sans solde", "unpaid", "sans_solde", "without", "wo pay", "unpay", "autre", "other");
            case COURTE_DUREE -> List.of(
                    "courte", "permission", "rtt", "short", "hour", "heure", "demi",
                    "recup", "récup", "recuperation", "récupération", "recovery", "compens", "compensation",
                    "repos compensateur", "time in lieu");
            default -> List.of();
        };
        return base.stream().map(this::normalize).distinct().toList();
    }

    private String normalizeNullable(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private double safe0(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) return 0;
        return v;
    }

    private double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return 0d;
    }
}

