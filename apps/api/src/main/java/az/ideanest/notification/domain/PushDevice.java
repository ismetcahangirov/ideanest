package az.ideanest.notification.domain;

import az.ideanest.shared.Identifiers;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * One installation of the mobile application, and where to reach it — issue #87.
 *
 * <p><strong>An installation, not a device and not a person.</strong> Reinstalling
 * produces a new token; a phone handed to somebody else keeps the old one until it does.
 * That is why the token is unique rather than the pair of it and the account, and why
 * registering a token that already exists MOVES it rather than adding a second row —
 * {@code PushDevices} makes that argument, and it is a disclosure question rather than a
 * tidiness one.
 *
 * <p>Mutable, like {@link NotificationPreference} and for the same reason: this is a
 * current state rather than a record of the past. Nothing keeps the history of which
 * account a token used to belong to, because that history is a log of who has held a
 * phone and nothing in §17.4 asks for one.
 */
@Entity
@Table(name = "push_devices")
public class PushDevice {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Rewritten when the same installation is registered by a different account. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, updatable = false)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private DevicePlatform platform;

    @Column(name = "device_name")
    private String deviceName;

    @Column(name = "app_version")
    private String appVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    protected PushDevice() {
        // JPA.
    }

    private PushDevice(
            UUID userId,
            String token,
            DevicePlatform platform,
            String deviceName,
            String appVersion,
            Instant now) {
        this.id = Identifiers.newIdentifier();
        this.userId = Objects.requireNonNull(userId, "A registration belongs to somebody");
        this.token = Objects.requireNonNull(token, "A registration is an address");
        this.platform = Objects.requireNonNull(platform, "A registration names its platform");
        this.deviceName = deviceName;
        this.appVersion = appVersion;
        this.createdAt = stored(now);
        this.lastSeenAt = stored(now);
    }

    /** A first registration of this installation. */
    public static PushDevice register(
            UUID userId,
            String token,
            DevicePlatform platform,
            String deviceName,
            String appVersion,
            Instant now) {
        return new PushDevice(userId, token, platform, deviceName, appVersion, now);
    }

    /**
     * The same installation, registered again.
     *
     * <p>Everything except the token and the creation time is replaced, including the
     * account. See the class comment: a token that stayed with the previous account would
     * deliver that person's notifications to whoever is holding the phone now.
     */
    public void seen(UUID userId, DevicePlatform platform, String deviceName, String appVersion, Instant now) {
        this.userId = Objects.requireNonNull(userId, "A registration belongs to somebody");
        this.platform = Objects.requireNonNull(platform, "A registration names its platform");
        this.deviceName = deviceName;
        this.appVersion = appVersion;
        this.lastSeenAt = stored(now);
    }

    /**
     * The instant, at the precision the column can hold.
     *
     * <p>{@code timestamptz} stores microseconds and {@code Clock.instant()} on this
     * platform produces nanoseconds. Without this the entity in memory and the row in the
     * database disagree in the last three digits — so the response to a first registration
     * carries a different {@code registeredAt} from every later read of the same row, and a
     * client comparing the two concludes the installation was replaced.
     */
    private static Instant stored(Instant now) {
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getToken() {
        return token;
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    @Override
    public String toString() {
        // No token and no owner. A push token is an address that can be sent to, and
        // this record ends up in log lines about failed deliveries (§17.4).
        return "PushDevice[id=" + id + ", platform=" + platform + "]";
    }
}
