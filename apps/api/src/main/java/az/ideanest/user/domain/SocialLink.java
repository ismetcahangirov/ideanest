package az.ideanest.user.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One account somebody holds somewhere else — §4.2's P-03 (#276).
 *
 * <p><strong>Not a collection on {@link User}.</strong> The account is referenced by its
 * identifier rather than mapped as a {@code @OneToMany}, which is the shape every other
 * child row in this service takes. A managed collection would load on every path that
 * touches a user — sign-in, the admin list, the anonymisation job — to serve one page that
 * asks for it, and it would make the ordering and the delete-then-reinsert rewrite
 * {@code ProfileEditing} performs a fight with Hibernate's orphan removal rather than two
 * repository calls.
 *
 * <p>The URL is stored exactly as the person typed it, minus surrounding whitespace. It is
 * never fetched by this server and never resolved — see {@code OwnProfileResponse} — so
 * the only thing that can be checked about it is its shape, and the shape is checked in
 * two places on purpose: {@code ProfileEditing} refuses a non-https address with a 400 that
 * names the field, and {@code user_social_links_url_is_https} refuses the same value one
 * layer down so that no other write path can bypass the rule.
 */
@Entity
@Table(name = "user_social_links")
public class SocialLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private SocialPlatform platform;

    @Column(name = "url", nullable = false)
    private String url;

    /** Dense and zero-based. The service rewrites the whole list on every edit. */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected SocialLink() {
        // JPA.
    }

    private SocialLink(UUID id, UUID userId, SocialPlatform platform, String url, int position) {
        this.id = id;
        this.userId = userId;
        this.platform = platform;
        this.url = url;
        this.position = position;
    }

    public static SocialLink of(UUID userId, SocialPlatform platform, String url, int position) {
        return new SocialLink(
                Identifiers.newIdentifier(),
                Objects.requireNonNull(userId, "A social link belongs to an account"),
                Objects.requireNonNull(platform, "A social link names a platform"),
                Objects.requireNonNull(url, "A social link has an address"),
                position);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public SocialPlatform getPlatform() {
        return platform;
    }

    public String getUrl() {
        return url;
    }

    public int getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SocialLink link && Objects.equals(id, link.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        // No URL. This lands in logs, and a person's account elsewhere is personal
        // data that §17.4 redacts there.
        return "SocialLink[id=" + id + ", platform=" + platform + "]";
    }
}
