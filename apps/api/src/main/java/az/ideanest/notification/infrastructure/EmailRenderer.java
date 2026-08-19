package az.ideanest.notification.infrastructure;

import java.util.Locale;
import java.util.Set;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * {@link EmailContent} into the two bodies a message is sent with.
 *
 * <p>One layout in HTML and one in plain text, both under {@code resources/email/}, both
 * rendered from the same content — which is what keeps the two parts of a
 * {@code multipart/alternative} message saying the same thing.
 *
 * <p><strong>The engine is built here rather than injected from Spring Boot.</strong>
 * {@code spring-boot-starter-thymeleaf} is deliberately not a dependency: it configures
 * an engine for rendering HTTP responses and registers a {@code ViewResolver}, and this
 * service answers in JSON. What is wanted is the templating library and nothing else, so
 * the resolvers are declared explicitly and the engine has no idea a web request exists.
 */
@Component
public class EmailRenderer {

    /** Where the two layouts live, relative to the classpath root. */
    private static final String LOCATION = "email/";

    private static final String HTML_LAYOUT = "layout.html";

    private static final String TEXT_LAYOUT = "layout.txt";

    private final TemplateEngine engine;
    private final MessageSource messages;

    public EmailRenderer(MessageSource messages) {
        this.messages = messages;
        this.engine = engineFor();
    }

    /**
     * Both bodies of one email.
     *
     * <p>The subject is not rendered through a template: it is one line of copy that
     * {@link EmailComposer} has already resolved, and passing it through a template
     * engine would be a second place for a newline to get into a header.
     */
    public RenderedEmail render(EmailContent content) {
        Context context = new Context(Locale.ROOT);
        context.setVariable("content", content);
        // The two lines every email ends with, resolved here rather than put on
        // EmailContent: they are the same on every type, so a per-type field would be
        // twenty copies of one sentence.
        context.setVariable("footer", messages.getMessage("email.layout.footer", null, Locale.ROOT));
        context.setVariable("preferences", messages.getMessage("email.layout.preferences", null, Locale.ROOT));

        return new RenderedEmail(
                content.subject(),
                engine.process(TEXT_LAYOUT, context).strip(),
                engine.process(HTML_LAYOUT, context));
    }

    /**
     * The engine, with one resolver per template mode.
     *
     * <p>Two resolvers rather than two engines, distinguished by the file extension:
     * Thymeleaf decides the mode per resolver, and a single resolver would parse the
     * plain-text layout as HTML — which silently swallows everything between a
     * {@code <} and the next {@code >}.
     */
    private static TemplateEngine engineFor() {
        TemplateEngine engine = new TemplateEngine();
        engine.addTemplateResolver(resolver(TemplateMode.HTML, HTML_LAYOUT));
        engine.addTemplateResolver(resolver(TemplateMode.TEXT, TEXT_LAYOUT));
        return engine;
    }

    private static ClassLoaderTemplateResolver resolver(TemplateMode mode, String template) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(LOCATION);
        // No suffix: the caller names the file including its extension, which is also
        // what the resolvable pattern below matches on.
        resolver.setSuffix("");
        resolver.setTemplateMode(mode);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setResolvablePatterns(Set.of(template));
        // The templates are on the classpath and cannot change while the process runs,
        // so parsing each one once is the whole of it. Left at the default this would
        // re-check a jar entry's timestamp on every notification.
        resolver.setCacheable(true);
        return resolver;
    }
}
