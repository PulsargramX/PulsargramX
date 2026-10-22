/*
 * This file is a part of Telegram X
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.data;

import org.drinkless.tdlib.TdApi;

/** An independent edit session: incoming profile updates must not overwrite an unsaved address. */
public final class BusinessLocationDraft {
  public static final int MAX_ADDRESS_LENGTH = 96;

  private final TdApi.BusinessLocation original;
  private String address;
  private TdApi.Location location;
  private boolean mapAddress;

  public BusinessLocationDraft (TdApi.BusinessLocation original) {
    this.original = copy(original);
    this.address = original != null ? original.address : "";
    this.location = original != null ? copy(original.location) : null;
  }

  public String address () {
    return address;
  }

  public TdApi.Location location () {
    return copy(location);
  }

  public void setAddress (String address) {
    this.address = address;
    mapAddress = false;
  }

  public void setLocation (TdApi.Location location, String resolvedAddress) {
    this.location = copy(location);
    if (resolvedAddress != null && !resolvedAddress.isEmpty() &&
        (address.trim().isEmpty() || mapAddress)) {
      address = resolvedAddress;
      mapAddress = true;
    }
  }

  public boolean isEmpty () {
    return location == null && address.trim().isEmpty();
  }

  public boolean isValid () {
    String trimmed = address.trim();
    return isEmpty() || (!trimmed.isEmpty() && trimmed.length() <= MAX_ADDRESS_LENGTH);
  }

  public boolean hasChanges () {
    if (original == null) return !isEmpty();
    if (isEmpty() || !address.trim().equals(original.address)) return true;
    if (location == null || original.location == null) return location != original.location;
    return location.latitude != original.location.latitude ||
      location.longitude != original.location.longitude;
  }

  public TdApi.BusinessLocation toLocation () {
    if (!isValid()) throw new IllegalStateException("Invalid business address");
    return isEmpty() ? null : new TdApi.BusinessLocation(copy(location), address.trim());
  }

  public static TdApi.BusinessLocation fromUser (TdApi.UserFullInfo userFull) {
    return userFull != null && userFull.businessInfo != null ? userFull.businessInfo.location : null;
  }

  private static TdApi.BusinessLocation copy (TdApi.BusinessLocation value) {
    return value != null ? new TdApi.BusinessLocation(copy(value.location), value.address) : null;
  }

  private static TdApi.Location copy (TdApi.Location value) {
    return value != null ? new TdApi.Location(value.latitude, value.longitude,
      value.horizontalAccuracy) : null;
  }
}
