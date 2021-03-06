package org.thoughtcrime.securesms.database.loaders;


import android.content.Context;
import android.database.Cursor;
import android.support.annotation.NonNull;

import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.Database;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.AbstractCursorLoader;

public class ThreadMediaLoader extends AbstractCursorLoader {

  private final Address      address;
  private final MasterSecret masterSecret;
  private final boolean      gallery;
  private final int typeId;

  public ThreadMediaLoader(@NonNull Context context, @NonNull MasterSecret masterSecret, @NonNull Address address, boolean gallery, int typeId) {
    super(context);
    this.masterSecret = masterSecret;
    this.address      = address;
    this.gallery      = gallery;
    this.typeId       = typeId;
  }

  @Override
  public Cursor getCursor() {
    if (typeId > -1) {
      String type = null;
      if (typeId == 0)
        type = "image";
      else if (typeId == 1)
        type = "video";

      return DatabaseFactory.getMediaDatabase(getContext()).getMediaByMimeType(type);
    } else {
      long threadId = DatabaseFactory.getThreadDatabase(getContext()).getThreadIdFor(Recipient.from(getContext(), address, true));

      if (gallery) return DatabaseFactory.getMediaDatabase(getContext()).getGalleryMediaForThread(threadId);
      else         return DatabaseFactory.getMediaDatabase(getContext()).getDocumentMediaForThread(threadId);
    }
  }

  public Address getAddress() {
    return address;
  }

  public MasterSecret getMasterSecret() {
    return masterSecret;
  }
}