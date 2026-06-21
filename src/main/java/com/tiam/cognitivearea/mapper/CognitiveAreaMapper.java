package com.tiam.cognitivearea.mapper;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.cognitivearea.dto.CognitiveAreaResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CognitiveAreaMapper {
    CognitiveAreaResponse toResponse(CognitiveArea area);
    List<CognitiveAreaResponse> toResponseList(List<CognitiveArea> areas);
}
