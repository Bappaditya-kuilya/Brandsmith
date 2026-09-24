package com.brandsmith.api.messages;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class MessagesValidationTest {

    private final Validator validator = Validation.byDefaultProvider().configure()
            .buildValidatorFactory().getValidator();

    private static TaglineOption option(String text) {
        return new TaglineOption(text, 80,
                List.of(new TaglineAttempt(text, 80, 0)));
    }

    private static MessagesOutput valid() {
        return new MessagesOutput(
                List.of(option("Skip the scramble"), option("Form the group early"), option("Match deadlines")),
                "One-line pitch for the brand.",
                new MessageHierarchy("Primary claim", List.of("Support A", "Support B"),
                        List.of("Proof one")));
    }

    @Test
    void validOutputHasNoViolations() {
        Set<ConstraintViolation<MessagesOutput>> violations = validator.validate(valid());
        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test
    void twoTaglinesFailSizeValidation() {
        MessagesOutput tooFew = new MessagesOutput(
                List.of(option("One"), option("Two")),
                valid().pitch(), valid().hierarchy());
        Set<String> paths = paths(tooFew);
        assertTrue(paths.contains("taglines"), paths::toString);
    }

    @Test
    void blankPitchFails() {
        MessagesOutput blank = new MessagesOutput(valid().taglines(), "  ", valid().hierarchy());
        assertTrue(paths(blank).contains("pitch"));
    }

    @Test
    void emptyProofFails() {
        MessagesOutput empty = new MessagesOutput(valid().taglines(), valid().pitch(),
                new MessageHierarchy("Primary", List.of("A"), List.of()));
        assertTrue(paths(empty).contains("hierarchy.proof"));
    }

    private Set<String> paths(MessagesOutput output) {
        return validator.validate(output).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }
}
