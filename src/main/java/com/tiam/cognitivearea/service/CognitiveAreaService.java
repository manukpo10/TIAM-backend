package com.tiam.cognitivearea.service;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.cognitivearea.dto.CognitiveAreaResponse;
import com.tiam.cognitivearea.mapper.CognitiveAreaMapper;
import com.tiam.cognitivearea.repository.CognitiveAreaRepository;
import com.tiam.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CognitiveAreaService {

    private final CognitiveAreaRepository cognitiveAreaRepository;
    private final CognitiveAreaMapper cognitiveAreaMapper;

    @Transactional(readOnly = true)
    public List<CognitiveAreaResponse> findAll() {
        return cognitiveAreaMapper.toResponseList(cognitiveAreaRepository.findAll());
    }

    @Transactional(readOnly = true)
    public CognitiveArea findEntityById(Long id) {
        return cognitiveAreaRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("CognitiveArea not found: " + id));
    }
}
