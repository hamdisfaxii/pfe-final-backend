package com.example.conges.repository;

import com.example.conges.entity.EmployeeLeaveAllocation;
import com.example.conges.entity.LeaveType;
import com.example.conges.entity.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EmployeeLeaveAllocationRepository extends JpaRepository<EmployeeLeaveAllocation, Long> {

    Optional<EmployeeLeaveAllocation> findByDolibarrAllocationId(Long dolibarrAllocationId);

    List<EmployeeLeaveAllocation> findByEmployeeAndAnnee(UserEntity employee, Integer annee);

    List<EmployeeLeaveAllocation> findByEmployeeAndLeaveTypeAndAnnee(
            UserEntity employee,
            LeaveType leaveType,
            Integer annee);

    Optional<EmployeeLeaveAllocation> findByEmployeeAndLeaveTypeAndAnneeAndActiveTrue(
            UserEntity employee,
            LeaveType leaveType,
            Integer annee);

    @Query("SELECT a FROM EmployeeLeaveAllocation a WHERE a.employee = :employee AND a.annee = :annee ORDER BY a.leaveType.libelle")
    List<EmployeeLeaveAllocation> findAllAllocationsForYear(UserEntity employee, Integer annee);

    @Query("SELECT SUM(a.joursDisponibles) FROM EmployeeLeaveAllocation a WHERE a.employee = :employee AND a.annee = :annee AND a.active = true")
    Double getTotalJoursDisponibles(UserEntity employee, Integer annee);

    @Query("""
            SELECT a
            FROM EmployeeLeaveAllocation a
            JOIN FETCH a.employee e
            JOIN FETCH a.leaveType lt
            WHERE e.id IN :employeeIds
              AND a.annee = :annee
            """)
    List<EmployeeLeaveAllocation> findByEmployeeIdsAndAnnee(
            @Param("employeeIds") List<Long> employeeIds,
            @Param("annee") Integer annee);

    @Query("SELECT a FROM EmployeeLeaveAllocation a JOIN a.employee e JOIN a.leaveType lt WHERE e.id = :userId AND lt.id = :leaveTypeId")
    Optional<EmployeeLeaveAllocation> findByUserIdAndLeaveTypeId(
            @Param("userId") Long userId,
            @Param("leaveTypeId") String leaveTypeId);
}
