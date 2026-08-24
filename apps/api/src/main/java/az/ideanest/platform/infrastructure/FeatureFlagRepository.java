package az.ideanest.platform.infrastructure;

import az.ideanest.platform.domain.FeatureFlag;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * V50's flags — #312.
 *
 * <p>Two reads and the inherited {@code save}. There is no delete: a flag is switched off
 * rather than removed, because code that asks for a flag that no longer exists gets the
 * code default silently, and "somebody deleted the row" and "somebody never created it"
 * then look identical from the application's side.
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {

    /**
     * Every flag, alphabetically.
     *
     * <p>Read whole on every evaluation and cached by {@code FeatureFlags} for a few
     * seconds — see that class on why a cache is acceptable here and is not on
     * {@code StaffDirectory}. There are tens of rows, and this is the read that would
     * otherwise sit in front of every page render on the platform.
     */
    @Query("SELECT f FROM FeatureFlag f ORDER BY f.key ASC")
    List<FeatureFlag> allFlags();
}
