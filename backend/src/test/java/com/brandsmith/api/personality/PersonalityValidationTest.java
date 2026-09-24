package com.brandsmith.api.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

class PersonalityValidationTest {

    private static final String VALID = """
            {
              "traits": [
                {"name": "Blunt", "whyFits": "brief: \\"hates waste\\"", "behavior": "Price first", "neverBecome": "rude"},
                {"name": "Warm", "whyFits": "brief: \\"stressed students\\"", "behavior": "Small wins", "neverBecome": "patronizing"},
                {"name": "Practical", "whyFits": "brief: \\"deadline\\"", "behavior": "Next step first", "neverBecome": "preachy"}
              ],
              "voice": {
                "formality": 3,
                "sentenceWords": [6, 16],
                "humorLevel": "dry",
                "bannedWords": ["a", "b", "c", "d", "e"],
                "signatureMoves": ["Open problem", "One number", "Single ask"]
              },
              "avoidList": ["rude", "patronizing", "preachy"]
            }""";

    private final Validator validator = Validation.byDefaultProvider().configure()
            .buildValidatorFactory().getValidator();
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    private PersonalityOutput parse(String json) throws Exception {
        return mapper.readValue(json, PersonalityOutput.class);
    }

    private Set<String> paths(PersonalityOutput output) {
        return validator.validate(output).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    @Test
    void validOutputHasNoViolations() throws Exception {
        Set<ConstraintViolation<PersonalityOutput>> violations = validator.validate(parse(VALID));
        assertTrue(violations.isEmpty(), violations::toString);
    }

    @Test
    void twoTraitsFailsSizeValidation() throws Exception {
        PersonalityOutput output = parse(VALID);
        PersonalityOutput tooFew = new PersonalityOutput(
                output.traits().subList(0, 2), output.voice(), output.avoidList());
        assertTrue(paths(tooFew).contains("traits"));
    }

    @Test
    void sixTraitsFailsSizeValidation() throws Exception {
        PersonalityOutput output = parse(VALID);
        List<Trait> six = List.of(
                output.traits().get(0), output.traits().get(1), output.traits().get(2),
                new Trait("A", "why", "b", "n"),
                new Trait("B", "why", "b", "n"),
                new Trait("C", "why", "b", "n"));
        PersonalityOutput tooMany = new PersonalityOutput(six, output.voice(), output.avoidList());
        assertTrue(paths(tooMany).contains("traits"));
    }

    @Test
    void formalityZeroAndSixFail() throws Exception {
        PersonalityOutput output = parse(VALID);
        for (int bad : new int[] {0, 6}) {
            PersonalityOutput invalid = new PersonalityOutput(
                    output.traits(),
                    new VoiceSpec(bad, output.voice().sentenceWords(), output.voice().humorLevel(),
                            output.voice().bannedWords(), output.voice().signatureMoves()),
                    output.avoidList());
            assertTrue(paths(invalid).contains("voice.formality"), "formality " + bad);
        }
    }

    @Test
    void blankWhyFitsFails() throws Exception {
        PersonalityOutput output = parse(VALID);
        List<Trait> traits = List.of(
                new Trait("Blunt", "  ", "Price first", "rude"),
                output.traits().get(1),
                output.traits().get(2));
        Set<String> paths = paths(new PersonalityOutput(traits, output.voice(), output.avoidList()));
        assertTrue(paths.contains("traits[0].whyFits"), paths::toString);
    }

    @Test
    void signatureMovesMustBeExactlyThree() throws Exception {
        PersonalityOutput output = parse(VALID);
        VoiceSpec twoMoves = new VoiceSpec(3, new int[] {6, 16}, "dry",
                List.of("a"), List.of("One", "Two"));
        assertTrue(paths(new PersonalityOutput(output.traits(), twoMoves, output.avoidList()))
                .contains("voice.signatureMoves"));
        VoiceSpec fourMoves = new VoiceSpec(3, new int[] {6, 16}, "dry",
                List.of("a"), List.of("One", "Two", "Three", "Four"));
        assertTrue(paths(new PersonalityOutput(output.traits(), fourMoves, output.avoidList()))
                .contains("voice.signatureMoves"));
    }

    @Test
    void humorLevelMustBeKnownValue() throws Exception {
        PersonalityOutput output = parse(VALID);
        VoiceSpec bad = new VoiceSpec(3, new int[] {6, 16}, "sarcastic",
                output.voice().bannedWords(), output.voice().signatureMoves());
        assertTrue(paths(new PersonalityOutput(output.traits(), bad, output.avoidList()))
                .contains("voice.humorLevel"));
    }
}
