package az.ideanest.shared.observability;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Carries the correlation identifiers onto a worker thread, or mints a set when
 * there is nothing to carry.
 *
 * <p>The MDC is thread-local, so work handed to an executor loses it. That is the
 * single most common way a log stream acquires uncorrelated lines: the request is
 * traceable, and the notification it sent afterwards is not.
 *
 * <p>Two cases, one rule.
 *
 * <ul>
 *   <li>Submitted from a request — the request's identifiers are copied, so the
 *       asynchronous half of a pledge reads as part of the pledge.</li>
 *   <li>Submitted by the scheduler — there are none to copy, so a set is minted
 *       <em>per run</em>. Per run rather than per schedule, because a cron task
 *       is decorated once and executed for the lifetime of the process, and one
 *       identifier shared by every sweep for a fortnight identifies nothing.</li>
 * </ul>
 *
 * <p>Whatever the worker thread had before is restored afterwards, not cleared:
 * the thread may belong to something else, and a decorator is not entitled to
 * assume it owns it.
 */
public class CorrelationTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> submitted = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> beforeThisTask = MDC.getCopyOfContextMap();
            try {
                if (submitted == null || submitted.isEmpty()) {
                    MDC.clear();
                    Correlation.mintIntoMdc();
                } else {
                    MDC.setContextMap(submitted);
                }
                runnable.run();
            } finally {
                if (beforeThisTask == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(beforeThisTask);
                }
            }
        };
    }
}
