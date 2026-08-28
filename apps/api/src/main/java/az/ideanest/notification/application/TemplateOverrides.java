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
     * <p>{@code "und"}, ISO 639-2 for an undetermined language. It was chosen to match the
     * {@link java.util.Locale#ROOT} {@code EmailComposer} rendered with, on the reasoning that
     * an override keyed to a language nobody was rendering in would be an override that never
     * applied — and that storing them under a guessed {@code "en"} would make every one of them
     * stop applying on the release that gave the sender a real locale.
     *
     * <p><strong>#324 was that release, and this key was right to be what it is.</strong> The
     * sender now takes the recipient's language off their account and the bundles carry all four
     * of §21.1's, so the shipped copy is translated. The overrides are not: one written here
     * applies to every recipient whatever they read, because {@code und} matches all of them.
     *
     * <p>That is a gap and it is deliberate rather than overlooked. An administrator who
     * rewrites a subject line today is rewriting it for everybody, which is what they could
     * already do and what the console's one text box means. Making an override per-language is
     * a screen with a language switcher on it, a migration that decides what the existing rows
     * mean, and a rule for what a recipient gets when their language has no override — three
     * product decisions, and #315's to take.
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
