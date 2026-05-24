package com.example.conges.entity;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "workflow_steps")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_definition_id", nullable = false)
    private WorkflowDefinition workflowDefinition;

    @Column(nullable = false)
    private Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WorkflowStepType stepType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role approverRole;

    @Column(nullable = false)
    private Boolean required;

    @Column(name = "min_days")
    private Integer minDays;

    @Column(name = "max_days")
    private Integer maxDays;

    @Column(name = "applicable_leave_types", length = 255)
    @javax.persistence.Convert(converter = TypeCongeSetConverter.class)
    private Set<TypeConge> applicableLeaveTypes;

    @Converter
    public static class TypeCongeSetConverter implements AttributeConverter<Set<TypeConge>, String> {
        @Override
        public String convertToDatabaseColumn(Set<TypeConge> attribute) {
            if (attribute == null || attribute.isEmpty()) {
                return null;
            }
            return attribute.stream()
                    .map(Enum::name)
                    .sorted()
                    .collect(Collectors.joining(","));
        }

        @Override
        public Set<TypeConge> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isBlank()) {
                return Set.of();
            }
            return Arrays.stream(dbData.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> {
                        try {
                            return TypeConge.valueOf(s.toUpperCase(Locale.ROOT));
                        } catch (IllegalArgumentException ignored) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
