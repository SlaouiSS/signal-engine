package org.signalengine.interfaces.rest.error;

/**
 * A requested REST resource does not exist. Controllers throw this when an application use case
 * reports the target as absent (an empty {@code Optional}); {@link ApiExceptionHandler} maps it to
 * a {@code 404} problem response.
 */
public class ResourceNotFoundException extends RuntimeException {

  public ResourceNotFoundException(String resource, Object identifier) {
    super("No %s with id '%s'".formatted(resource, identifier));
  }
}
