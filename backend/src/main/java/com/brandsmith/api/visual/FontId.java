package com.brandsmith.api.visual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.brandsmith.api.det.SvgLogoRenderer;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

@Target({ElementType.TYPE_USE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FontId.Check.class)
public @interface FontId {

    String message() default "not in fonts-allowed.txt";

    Class<?>[] groups() default {};

    Class<?>[] payload() default {};

    class Check implements ConstraintValidator<FontId, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return SvgLogoRenderer.isAllowedFont(value);
        }
    }
}
