package com.tiam.cognitivearea.mapper;

import com.tiam.cognitivearea.domain.CognitiveArea;
import com.tiam.cognitivearea.dto.CognitiveAreaResponse;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-21T04:32:04-0300",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.7 (Oracle Corporation)"
)
@Component
public class CognitiveAreaMapperImpl implements CognitiveAreaMapper {

    @Override
    public CognitiveAreaResponse toResponse(CognitiveArea area) {
        if ( area == null ) {
            return null;
        }

        Long id = null;
        String slug = null;
        String name = null;

        id = area.getId();
        slug = area.getSlug();
        name = area.getName();

        CognitiveAreaResponse cognitiveAreaResponse = new CognitiveAreaResponse( id, slug, name );

        return cognitiveAreaResponse;
    }

    @Override
    public List<CognitiveAreaResponse> toResponseList(List<CognitiveArea> areas) {
        if ( areas == null ) {
            return null;
        }

        List<CognitiveAreaResponse> list = new ArrayList<CognitiveAreaResponse>( areas.size() );
        for ( CognitiveArea cognitiveArea : areas ) {
            list.add( toResponse( cognitiveArea ) );
        }

        return list;
    }
}
