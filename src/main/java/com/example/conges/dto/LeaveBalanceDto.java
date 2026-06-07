package com.example.conges.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LeaveBalanceDto {
    private Long userId;
    private String leaveType;
    private double initialDays;
    private double daysUsed;
    private double daysPending;
    private double availableDays;
    private double effectiveAvailable;
    private LocalDateTime calculatedAt;
}
