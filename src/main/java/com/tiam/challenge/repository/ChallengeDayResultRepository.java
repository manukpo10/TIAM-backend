package com.tiam.challenge.repository;

import com.tiam.challenge.domain.ChallengeDayResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChallengeDayResultRepository extends JpaRepository<ChallengeDayResult, Long> {

    Optional<ChallengeDayResult> findByChallengePurchaseIdAndDayAndActivoTrue(Long challengePurchaseId, int day);

    List<ChallengeDayResult> findByChallengePurchaseIdAndActivoTrueOrderByDayAsc(Long challengePurchaseId);
}
