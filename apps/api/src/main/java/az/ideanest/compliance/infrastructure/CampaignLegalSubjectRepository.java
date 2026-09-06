package az.ideanest.compliance.infrastructure;

import az.ideanest.compliance.domain.CampaignLegalSubject;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** What campaigns were submitted under (#430). Keyed by the campaign. */
public interface CampaignLegalSubjectRepository extends JpaRepository<CampaignLegalSubject, UUID> {

    /** "What has this creator submitted under", for the console and for a dispute. */
    List<CampaignLegalSubject> findByUserIdOrderByFrozenAtDesc(UUID userId);
}
