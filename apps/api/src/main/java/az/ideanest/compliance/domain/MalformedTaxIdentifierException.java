package az.ideanest.compliance.domain;

/**
 * A VÖEN that is not ten digits — issue #430.
 *
 * <p>Carries what was typed so the creator can be shown it back beside the refusal. It is not
 * a secret and it is not personal data of a person: it identifies a company, and a refusal
 * that does not repeat what it refused makes the creator retype the field to find out what
 * they entered.
 */
public class MalformedTaxIdentifierException extends RuntimeException {

    private final String typed;

    public MalformedTaxIdentifierException(String typed) {
        super("A VÖEN is ten digits; got " + (typed == null ? "nothing" : "'" + typed + "'"));
        this.typed = typed;
    }

    public String typed() {
        return typed;
    }
}
