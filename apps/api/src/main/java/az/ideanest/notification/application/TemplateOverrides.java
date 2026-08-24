package az.ideanest.notification.application;

import az.ideanest.notification.domain.EmailTemplateVersion;
import az.ideanest.notification.infrastructure.EmailTemplateVersionRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the platform sends when somebody has rewritten it — §12.3 and AD-15, issue #315.
 *
 * <h2>Overrides, not templates</h2>
 *
 * <p>V52's header has the argument at length. The message catalogue stays authoritative:
 * it is translated, reviewed and shipped with the code that reads it, so a template living
 * only in the database would mean a deployment that added a notification type shipped code
 * rendering a template nobody had inserted yet — and the first missing row is a
 * notification that cannot be sent.
 *
 * <p>So a row exists only for copy somebody has edited, and its absence means "send what
 * the code says". That is what makes this class a lookup with a miss rather than a
 * resolver with a fallback.
 *
 * <h2>What can be edited, and what cannot</h2>
 *
 * <p>The subject and the body paragraph. The headline, the button label and the digest's
 * lines stay in the catalogue, and that is a scope decision rather than an oversight: those
 * three are structural — a button with no label is a broken email rather than a badly
 * worded one — and §12.3's argument about editing is about the words a recipient reads.
 *
 * <p><strong>Placeholders are checked on write, not here.</strong>
 * {@code EmailTemplateEditor} refuses an override whose body drops a {@code {n}} the
 * shipped copy carries — the payment-failure notice that no longer says which card was
 * declined is the case #315 named, and no role check catches it because the administrator
 * editing it is exactly the person allowed to.
 *
 * <h2>The cache</h2>
 *
 * <p>Every override in one map, replaced whole on an edit. Loaded lazily and never
 * expiring on a timer, unlike {@code FeatureFlags}: an edit goes through
 * {@code EmailTemplateEditor}, which clears it, and there is no other writer — where a flag
 * can be changed on another instance and has to be re-read. What that leaves is the window
 * between one instance's edit and another instance's next restart, which is why
 * {@link #reload} is public and the editor calls it.
 */
@Service
public class TemplateOverrides {

    /**
     * The locale an override is stored and looked up under — #315.
     *
     * <p>{@code "und"}, ISO 639-2 for an undetermined language, matching the
     * {@link java.util.Locale#ROOT} that {@code EmailComposer} renders with. Every email
     * the platform sends today comes out of the root bundle, because the sender is a
     * background job with no reader attached and {@code users.locale} is not read yet —
     * so an override has to be keyed to the same thing rather than to a language nobody
     * is rendering in.
     *
     * <p><strong>Storing overrides under {@code "en"} today would be the expensive
     * mistake.</strong> When #123 gives the sender the recipient's locale, every override
     * written under a guessed code would silently stop applying on that release, and the
     * symptom would be the platform quietly reverting to shipped copy nobody had asked it
     * to send. Under {@code und} they stay findable and can be migrated deliberately.
     *
     * <p>It lives here rather than on {@code EmailComposer} because the API layer needs it
     * too, and {@code ModuleBoundaryTests} keeps the api package out of infrastructure.
     */
    public static final String RENDER_LOCALE = "und";

    private final EmailTemplateVersionRepository versions;

    /**
     * Every live override, keyed by {@code templateKey + ':' + locale}.
     *
     * <p>Null until first read. A null map and an empty one are different states here —
     * empty means "there are no overrides", and null means "nobody has looked yet" — and
     * conflating them would make the first message the platform sends after a restart
     * ignore every override in the table.
     */
    private final AtomicReference<Map<String, EmailTemplateVersion>> cache = new AtomicReference<>();

    public TemplateOverrides(EmailTemplateVersionRepository versions) {
        this.versions = versions;
    }

    /**
     * The edited subject for this template, if there is one.
     *
     * @param locale §21.1's code. An override is per language, because that is what a
     *     translation is — editing the English does not silently blank the Azerbaijani
     */
    public Optional<String> subjectFor(String templateKey, String locale) {
        return liveFor(templateKey, locale).map(EmailTemplateVersion::subject);
    }

    /** The edited body paragraph, if there is one. */
    public Optional<String> bodyFor(String templateKey, String locale) {
        return liveFor(templateKey, locale).map(EmailTemplateVersion::body);
    }

    /** The whole live version, for the preview that shows what will actually be sent. */
    public Optional<EmailTemplateVersion> liveFor(String templateKey, String locale) {
        return Optional.ofNullable(current().get(key(templateKey, locale)));
    }

    /** Drops the cache. Called by the editor after every write. */
    public void reload() {
        cache.set(null);
    }

    @Transactional(readOnly = true)
    Map<String, EmailTemplateVersion> current() {
        Map<String, EmailTemplateVersion> held = cache.get();
        if (held != null) {
            return held;
        }

        Map<String, EmailTemplateVersion> loaded = new HashMap<>();
        for (EmailTemplateVersion version : versions.allLive()) {
            loaded.put(key(version.templateKey(), version.locale()), version);
        }

        Map<String, EmailTemplateVersion> immutable = Map.copyOf(loaded);
        cache.compareAndSet(null, immutable);

        // The winner's map is returned rather than this one, so that two threads loading at
        // once cannot hand out two different snapshots of the same table.
        Map<String, EmailTemplateVersion> settled = cache.get();
        return settled == null ? immutable : settled;
    }

    private static String key(String templateKey, String locale) {
        return templateKey + ':' + locale;
    }
}
