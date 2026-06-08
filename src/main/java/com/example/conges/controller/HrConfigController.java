package com.example.conges.controller;

import com.example.conges.dto.config.CountryPolicyConfigRequest;
import com.example.conges.dto.config.EmployeeContractRhPatchRequest;
import com.example.conges.dto.config.ExceptionalLeaveConfigRequest;
import com.example.conges.dto.config.FranceRttSettingsDto;
import com.example.conges.dto.config.LeaveTypeConfigRequest;
import com.example.conges.dto.config.PublicHolidayCreateRequest;
import com.example.conges.dto.config.WorkScheduleConfigRequest;
import com.example.conges.dto.config.WorkScheduleConfigResponse;
import com.example.conges.entity.CountryLeavePolicy;
import com.example.conges.entity.ExceptionalLeaveConfig;
import com.example.conges.entity.Holiday;
import com.example.conges.entity.LeaveType;
import com.example.conges.entity.Role;
import com.example.conges.entity.UserEntity;
import com.example.conges.service.CountryPolicyService;
import com.example.conges.service.HrConfigService;
import com.example.conges.service.HrHolidayService;
import com.example.conges.service.HrWorkScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr-config")
@RequiredArgsConstructor
@PreAuthorize("hasRole('RH')")
public class HrConfigController {

    private final CountryPolicyService countryPolicyService;
    private final HrConfigService hrConfigService;
    private final HrHolidayService hrHolidayService;
    private final HrWorkScheduleService hrWorkScheduleService;

    @GetMapping("/country-policies")
    public ResponseEntity<List<CountryLeavePolicy>> getCountryPolicies() {
        return ResponseEntity.ok(hrConfigService.getCountryPolicies());
    }

    @PostMapping("/country-policies")
    public ResponseEntity<CountryLeavePolicy> upsertCountryPolicy(
            @Valid @RequestBody CountryPolicyConfigRequest request
    ) {
        return ResponseEntity.ok(countryPolicyService.upsertPolicy(
                request.getCountryCode(),
                request.getTypeConge(),
                request.getAnnualQuota(),
                request.getMonthlyAccrualRate(),
                request.getRttEnabled(),
                request.getRttAnnualDays()
        ));
    }

    @GetMapping("/leave-types")
    public ResponseEntity<List<LeaveType>> getLeaveTypes() {
        return ResponseEntity.ok(hrConfigService.getLeaveTypes());
    }

    @GetMapping("/france-rtt")
    public ResponseEntity<Map<String, Object>> getFranceRttAdmin() {
        Map<String, Object> body = new LinkedHashMap<>();
        FranceRttSettingsDto settingsDto = hrConfigService.getFranceRttSettings();
        body.put("accrualMode", settingsDto.getAccrualMode() == null ? null : settingsDto.getAccrualMode().name());
        body.put("adminOverrideDays", settingsDto.getAdminOverrideDays());
        body.put("updatedAt", settingsDto.getUpdatedAt());
        body.putAll(hrConfigService.franceRttFeatureFlags());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/france-rtt")
    public ResponseEntity<Map<String, Object>> updateFranceRttAdmin(
            @Valid @RequestBody FranceRttSettingsDto request
    ) {
        hrConfigService.upsertFranceRttSettings(request);
        FranceRttSettingsDto settingsDto = hrConfigService.getFranceRttSettings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accrualMode", settingsDto.getAccrualMode() == null ? null : settingsDto.getAccrualMode().name());
        body.put("adminOverrideDays", settingsDto.getAdminOverrideDays());
        body.put("updatedAt", settingsDto.getUpdatedAt());
        body.putAll(hrConfigService.franceRttFeatureFlags());
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/employees/{userId}/contract")
    public ResponseEntity<Map<String, Object>> patchEmployeeContract(
            @PathVariable Long userId,
            @RequestBody EmployeeContractRhPatchRequest request
    ) {
        UserEntity refreshed = hrConfigService.patchEmployeeContract(userId, request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", refreshed.getId());
        out.put("email", refreshed.getEmail());
        out.put("weeklyHours", refreshed.getWeeklyHours());
        out.put("annualWorkDays", refreshed.getAnnualWorkDays());
        out.put("contractType", refreshed.getContractType());
        out.put("contractActive", refreshed.isContractActive());
        out.put("countryCode", refreshed.getCountryCode());
        out.put("pays", refreshed.getPays());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/leave-types/{id}")
    public ResponseEntity<LeaveType> updateLeaveType(
            @PathVariable Long id,
            @Valid @RequestBody LeaveTypeConfigRequest request
    ) {
        return ResponseEntity.ok(hrConfigService.updateLeaveType(id, request));
    }

    @GetMapping("/integration-settings")
    public ResponseEntity<Map<String, String>> getIntegrationSettings() {
        return ResponseEntity.ok(hrConfigService.getIntegrationSettings());
    }

    @GetMapping("/exceptional-leaves")
    public ResponseEntity<List<ExceptionalLeaveConfig>> getExceptionalLeaves(
            @AuthenticationPrincipal UserEntity user,
            @RequestParam(name = "country", required = false) String country
    ) {
        return ResponseEntity.ok(hrConfigService.getExceptionalLeavesByCountry(resolveCountry(user, country)));
    }

    @PostMapping("/exceptional-leaves")
    public ResponseEntity<ExceptionalLeaveConfig> createExceptionalLeave(
            @AuthenticationPrincipal UserEntity user,
            @Valid @RequestBody ExceptionalLeaveConfigRequest request
    ) {
        ExceptionalLeaveConfig payload = ExceptionalLeaveConfig.builder()
                .countryCode(resolveCountry(user, request.getCountryCode()))
                .label(request.getLabel())
                .daysPerYear(request.getDaysPerYear())
                .enabled(request.getEnabled())
                .build();
        return ResponseEntity.ok(hrConfigService.createExceptionalLeave(payload));
    }

    @PutMapping("/exceptional-leaves/{id}")
    public ResponseEntity<ExceptionalLeaveConfig> updateExceptionalLeave(
            @PathVariable Long id,
            @AuthenticationPrincipal UserEntity user,
            @Valid @RequestBody ExceptionalLeaveConfigRequest request
    ) {
        ExceptionalLeaveConfig payload = ExceptionalLeaveConfig.builder()
                .countryCode(resolveCountry(user, request.getCountryCode()))
                .label(request.getLabel())
                .daysPerYear(request.getDaysPerYear())
                .enabled(request.getEnabled())
                .build();
        return ResponseEntity.ok(hrConfigService.updateExceptionalLeave(id, payload));
    }

    @DeleteMapping("/exceptional-leaves/{id}")
    public ResponseEntity<Map<String, Object>> deleteExceptionalLeave(
            @PathVariable Long id
    ) {
        hrConfigService.deleteExceptionalLeave(id);
        return ResponseEntity.ok(Map.of("success", true, "deletedId", id));
    }

    @GetMapping("/public-holidays")
    public ResponseEntity<List<Holiday>> getPublicHolidays(
            @AuthenticationPrincipal UserEntity user,
            @RequestParam(name = "country", required = false) String country,
            @RequestParam(name = "year", required = false) Integer year
    ) {
        return ResponseEntity.ok(hrHolidayService.listByCountryAndYear(resolveCountry(user, country), year));
    }

    @PostMapping("/public-holidays/import")
    public ResponseEntity<Map<String, Object>> importPublicHolidays(
            @AuthenticationPrincipal UserEntity user,
            @RequestParam(name = "country") String country,
            @RequestParam(name = "year") Integer year,
            @RequestBody(required = false) JsonNode ignoredBody
    ) {
        String resolvedCountry = resolveCountry(user, country);
        int imported = hrHolidayService.importPublicHolidays(resolvedCountry, year);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "imported", imported,
                "country", resolvedCountry,
                "year", year
        ));
    }

    @PostMapping("/public-holidays/import-all")
    public ResponseEntity<Map<String, Object>> importPublicHolidaysAll(
            @RequestParam(name = "year", required = false) Integer year,
            @RequestBody(required = false) JsonNode ignoredBody
    ) {
        return ResponseEntity.ok(hrHolidayService.importPublicHolidaysAllCountries(year));
    }

    @PostMapping("/public-holidays")
    public ResponseEntity<Holiday> createPublicHoliday(
            @AuthenticationPrincipal UserEntity user,
            @Valid @RequestBody PublicHolidayCreateRequest request
    ) {
        return ResponseEntity.ok(
                hrHolidayService.createPublicHoliday(
                        resolveCountry(user, request.getCountryCode()),
                        request.getLibelle(),
                        request.getDateJour()
                )
        );
    }

    @PutMapping("/public-holidays/{id}")
    public ResponseEntity<Holiday> updatePublicHoliday(
            @PathVariable Long id,
            @Valid @RequestBody PublicHolidayCreateRequest request
    ) {
        return ResponseEntity.ok(hrHolidayService.updatePublicHoliday(id, request.getLibelle(), request.getDateJour()));
    }

    @PutMapping("/public-holidays/{id}/apply")
    public ResponseEntity<Holiday> applyPublicHoliday(
            @PathVariable Long id,
            @RequestParam(name = "applied") boolean applied
    ) {
        return ResponseEntity.ok(hrHolidayService.setAppliedState(id, applied));
    }

    @DeleteMapping("/public-holidays/bulk")
    public ResponseEntity<Map<String, Object>> bulkDeletePublicHolidays(
            @RequestBody List<Long> ids
    ) {
        int deleted = hrHolidayService.bulkDelete(ids);
        return ResponseEntity.ok(Map.of("success", true, "deleted", deleted));
    }

    @PutMapping("/public-holidays/bulk/apply")
    public ResponseEntity<Map<String, Object>> bulkApplyPublicHolidays(
            @RequestBody Map<String, Object> body
    ) {
        @SuppressWarnings("unchecked")
        List<Object> rawIds = (List<Object>) body.get("ids");
        List<Long> ids = rawIds == null ? List.of() : rawIds.stream()
                .filter(Objects::nonNull)
                .map(o -> Long.valueOf(o.toString()))
                .collect(Collectors.toList());
        boolean applied = Boolean.TRUE.equals(body.get("applied"));
        int updated = hrHolidayService.bulkSetAppliedState(ids, applied);
        return ResponseEntity.ok(Map.of("success", true, "updated", updated));
    }

    @DeleteMapping("/public-holidays/{id}")
    public ResponseEntity<Map<String, Object>> deletePublicHoliday(@PathVariable Long id) {
        hrHolidayService.deleteHoliday(id);
        return ResponseEntity.ok(Map.of("success", true, "deletedId", id));
    }

    @GetMapping("/work-schedules")
    @PreAuthorize("hasAnyRole('RH','ADMIN','EMPLOYE','MANAGER')")
    public ResponseEntity<WorkScheduleConfigResponse> getWorkSchedules(
            @AuthenticationPrincipal UserEntity user,
            @RequestParam(name = "country", required = false) String country,
            @RequestParam(name = "type", required = false) String type
    ) {
        return ResponseEntity.ok(hrWorkScheduleService.getConfig(resolveCountry(user, country), type));
    }

    @PutMapping("/work-schedules")
    public ResponseEntity<WorkScheduleConfigResponse> updateWorkSchedules(
            @AuthenticationPrincipal UserEntity user,
            @RequestBody WorkScheduleConfigRequest request
    ) {
        request.setCountryCode(resolveCountry(user, request.getCountryCode()));
        return ResponseEntity.ok(hrWorkScheduleService.saveConfig(request));
    }

    private String resolveCountry(UserEntity user, String requestedCountry) {
        if (user != null && user.getRole() == Role.RH && requestedCountry != null && !requestedCountry.isBlank()) {
            return requestedCountry.trim().toUpperCase();
        }
        String userCountry = user == null ? null : user.getPays();
        if (userCountry == null || userCountry.isBlank()) {
            return requestedCountry == null ? "TN" : requestedCountry.trim().toUpperCase();
        }
        return userCountry.trim().toUpperCase();
    }
}


