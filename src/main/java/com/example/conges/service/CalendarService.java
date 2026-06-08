package com.example.conges.service;

import com.example.conges.dto.CalendarEventResponse;
import com.example.conges.entity.DemandeConge;
import com.example.conges.entity.Holiday;
import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.repository.DemandeCongeRepository;
import com.example.conges.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private static final Set<Role> CALENDAR_ROLES =
            EnumSet.of(Role.EMPLOYE, Role.RH);

    private final DemandeCongeRepository demandeCongeRepository;
    private final HolidayRepository holidayRepository;
    private final CountryPolicyService countryPolicyService;

    @Transactional(readOnly = true)
    public List<CalendarEventResponse> getEvents(
            UserEntity actor,
            LocalDate startDate,
            LocalDate endDate,
            Long employeeId,
            String department,
            String country
    ) {
        assertActorMayUseCalendar(actor);
        List<CalendarEventResponse> events = new ArrayList<>();
        String geoCountry = resolveCountry(actor, country);

        Long effectiveEmployeeId = employeeId;
        String effectiveDepartment = normalizeOptional(department);
        if (actor != null && actor.getRole() == Role.EMPLOYE) {
            effectiveEmployeeId = actor.getId();
            effectiveDepartment = null;
        }

        /* FÃ©riÃ©s : filtre pays mÃ©tier (souvent ISO2). CongÃ©s : pour l'employÃ©, l'ID suffit ; u.pays peut Ãªtre libellÃ© (Â« France Â») et ne pas matcher Â« FR Â». */
        String leaveCountryFilter = actor.getRole() == Role.EMPLOYE ? null : geoCountry;

        List<DemandeConge> leaves = demandeCongeRepository.findApprovedForCalendar(
                startDate,
                endDate,
                effectiveEmployeeId,
                effectiveDepartment,
                leaveCountryFilter
        );
        for (DemandeConge leave : leaves) {
            String fullName = (leave.getUser().getPrenom() + " " + leave.getUser().getNom()).trim();
            events.add(CalendarEventResponse.builder()
                    .eventType("APPROVED_LEAVE")
                    .demandeId(leave.getId())
                    .userId(leave.getUser().getId())
                    .employeeName(fullName)
                    .department(leave.getUser().getDepartement())
                    .country(leave.getUser().getPays())
                    .leaveType(leave.getTypeConge().name())
                    .title(fullName + " - " + leave.getTypeConge().getLibelle())
                    .startDate(leave.getDateDebut())
                    .endDate(leave.getDateFin())
                    .build());
        }

        List<DemandeConge> pendingLeaves = demandeCongeRepository.findPendingForCalendar(
                startDate,
                endDate,
                effectiveEmployeeId,
                effectiveDepartment,
                leaveCountryFilter
        );
        for (DemandeConge leave : pendingLeaves) {
            String fullName = (leave.getUser().getPrenom() + " " + leave.getUser().getNom()).trim();
            events.add(CalendarEventResponse.builder()
                    .eventType("MY_LEAVE_PENDING")
                    .demandeId(leave.getId())
                    .userId(leave.getUser().getId())
                    .employeeName(fullName)
                    .department(leave.getUser().getDepartement())
                    .country(leave.getUser().getPays())
                    .leaveType(leave.getTypeConge().name())
                    .title("(En attente) " + fullName + " - " + leave.getTypeConge().getLibelle())
                    .startDate(leave.getDateDebut())
                    .endDate(leave.getDateFin())
                    .build());
        }

        List<Holiday> holidays = holidayRepository.findByDateRangeWithOptionalCountry(
                startDate,
                endDate,
                geoCountry
        );
        for (Holiday holiday : holidays) {
            events.add(CalendarEventResponse.builder()
                    .eventType("HOLIDAY")
                    .title(holiday.getLibelle())
                    .country(holiday.getCountryCode())
                    .startDate(holiday.getDateJour())
                    .endDate(holiday.getDateJour())
                    .build());
        }
        return events;
    }

    private void assertActorMayUseCalendar(UserEntity actor) {
        if (actor == null) {
            throw new AccessDeniedException("Authentification requise");
        }
        Role role = actor.getRole();
        if (role == null || !CALENDAR_ROLES.contains(role)) {
            throw new AccessDeniedException("RÃ´le non autorisÃ© pour le calendrier");
        }
    }

    private String normalizeOptionalCountry(String country) {
        if (country == null || country.isBlank()) {
            return null;
        }
        return country.trim().toUpperCase();
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String resolveCountry(UserEntity actor, String requestedCountry) {
        if (actor != null && actor.getRole() == Role.RH) {
            return normalizeOptionalCountry(requestedCountry);
        }
        if (actor == null) {
            return normalizeOptionalCountry(requestedCountry);
        }
        String actorIso = countryPolicyService.normalizeBusinessCountry(actor.getPays());
        String requested = normalizeOptionalCountry(requestedCountry);
        if (requested != null && requested.equals(actorIso)) {
            return requested;
        }
        return actorIso;
    }
}

