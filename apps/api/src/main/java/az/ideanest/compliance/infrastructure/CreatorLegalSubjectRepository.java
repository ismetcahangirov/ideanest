package az.ideanest.compliance.infrastructure;

import az.ideanest.compliance.domain.CreatorLegalSubject;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per account (#430). The account is the key, so the derived finders are inherited. */
public interface CreatorLegalSubjectRepository extends JpaRepository<CreatorLegalSubject, UUID> {
}
