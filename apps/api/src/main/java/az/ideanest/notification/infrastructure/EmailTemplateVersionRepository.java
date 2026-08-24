package az.ideanest.notification.infrastructure;

import az.ideanest.notification.domain.EmailTemplateVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * V52's overrides — issue #315.
 *
 * <p><strong>{@link #liveFor} is on the render path</strong> and is a single index lookup
 * against {@code email_template_versions_one_live}. Most templates have no override at all,
 * so the common answer is an empty result from an index probe — which is what makes it
 * acceptable to ask on every message the platform sends. {@code TemplateOverrides} caches
 * the answers on top of it.
 */
public interface EmailTemplateVersionRepository extends JpaRepository<EmailTemplateVersion, UUID> {

    /** The version that renders, if this template and locale have been edited. */
    @Query(
            """
            SELECT v FROM EmailTemplateVersion v
            WHERE v.templateKey = :templateKey AND v.locale = :locale AND v.live = true
            """)
    Optional<EmailTemplateVersion> liveFor(
            @Param("templateKey") String templateKey, @Param("locale") String locale);

    /** Every live override, for the cache to load in one query rather than one per type. */
    @Query("SELECT v FROM EmailTemplateVersion v WHERE v.live = true")
    List<EmailTemplateVersion> allLive();

    /** The history of one template in one locale, newest first. */
    @Query(
            """
            SELECT v FROM EmailTemplateVersion v
            WHERE v.templateKey = :templateKey AND v.locale = :locale
            ORDER BY v.version DESC
            """)
    List<EmailTemplateVersion> historyOf(
            @Param("templateKey") String templateKey, @Param("locale") String locale);

    /**
     * The highest version written for this template and locale.
     *
     * <p>Read inside the transaction that writes the next one. V52's unique constraint on
     * {@code (template_key, locale, version)} is what makes two concurrent edits produce a
     * refusal rather than two rows claiming to be version three — this read is the fast
     * path and the constraint is the guarantee.
     */
    @Query(
            """
            SELECT COALESCE(MAX(v.version), 0) FROM EmailTemplateVersion v
            WHERE v.templateKey = :templateKey AND v.locale = :locale
            """)
    int highestVersionOf(@Param("templateKey") String templateKey, @Param("locale") String locale);
}
