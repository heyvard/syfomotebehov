package no.nav.syfo.repository;

import org.owasp.html.PolicyFactory;
import org.owasp.html.HtmlPolicyBuilder;
import org.slf4j.Logger;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static java.util.Optional.ofNullable;
import static org.slf4j.LoggerFactory.getLogger;
import static org.apache.commons.lang3.StringEscapeUtils.unescapeHtml4;

public class DbUtil {

    private static final Logger LOG = getLogger(DbUtil.class);

    public static Timestamp convert(LocalDateTime timestamp) {
        return ofNullable(timestamp).map(Timestamp::valueOf).orElse(null);
    }

    public static LocalDateTime convert(Timestamp timestamp) {
        return ofNullable(timestamp).map(Timestamp::toLocalDateTime).orElse(null);
    }

    private static PolicyFactory sanitizer = new HtmlPolicyBuilder().toFactory();

    public static String sanitizeUserInput(String userinput) {
        String sanitizedInput = unescapeHtml4(sanitizer.sanitize(unescapeHtml4(userinput)));
        if (!sanitizedInput.equals(userinput) && userinput != null) {
            LOG.warn("Dette er ikke en feil, men burde vært stoppet av regexen i frontend. Finn ut hvorfor og evt. oppdater regex. \n" +
                    "Det ble strippet vekk innhold slik at denne teksten: {} \n" +
                    "ble til denne teksten: {}", userinput, sanitizedInput);
        }
        return sanitizedInput;
    }
}