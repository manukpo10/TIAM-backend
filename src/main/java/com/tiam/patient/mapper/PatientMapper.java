package com.tiam.patient.mapper;

import com.tiam.patient.domain.Patient;
import com.tiam.patient.dto.CreatePatientRequest;
import com.tiam.patient.dto.PatientResponse;
import com.tiam.patient.dto.UpdatePatientRequest;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PatientMapper {

    @Mapping(target = "homeSubscriptionActive", source = "homeSubscriptionActive")
    PatientResponse toResponse(Patient patient, boolean homeSubscriptionActive);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "activo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "professionalId", ignore = true)
    @Mapping(target = "lastSessionAt", ignore = true)
    @Mapping(target = "playToken", ignore = true)
    Patient toEntity(CreatePatientRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "activo", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "professionalId", ignore = true)
    @Mapping(target = "lastSessionAt", ignore = true)
    @Mapping(target = "playToken", ignore = true)
    void updateEntity(UpdatePatientRequest request, @MappingTarget Patient patient);
}
