package org.signalengine.interfaces.rest.areaofinterest;

import org.signalengine.domain.AreaOfInterest;

/**
 * API representation of one of the six fixed areas of interest (docs/05-data-model.md Section 5).
 */
public record AreaOfInterestResponse(String code, String name) {

  public static AreaOfInterestResponse from(AreaOfInterest areaOfInterest) {
    return new AreaOfInterestResponse(areaOfInterest.code(), areaOfInterest.name());
  }
}
