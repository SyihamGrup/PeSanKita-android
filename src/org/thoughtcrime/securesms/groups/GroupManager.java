package org.thoughtcrime.securesms.groups;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.protobuf.ByteString;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.crypto.MasterSecret;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.mms.OutgoingGroupMediaMessage;
import org.thoughtcrime.securesms.providers.SingleUseBlobProvider;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.util.InvalidNumberException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class GroupManager {

  public static @NonNull GroupActionResult createGroup(@NonNull  Context        context,
                                                       @NonNull  MasterSecret   masterSecret,
                                                       @NonNull  Set<Recipient> members,
                                                       @Nullable Bitmap         avatar,
                                                       @Nullable String         name,
                                                                 boolean mms)
  {
    final byte[]        avatarBytes     = BitmapUtil.toByteArray(avatar);
    final GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    final String        groupId         = GroupUtil.getEncodedId(groupDatabase.allocateGroupId(), mms);
    final Recipient     groupRecipient  = Recipient.from(context, Address.fromSerialized(groupId), false);
    final Set<Address>  memberAddresses = getMemberAddresses(members);
    final Address       ownerAddress    = Address.fromSerialized(TextSecurePreferences.getLocalNumber(context));

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
	  groupDatabase.create(groupId, name, new LinkedList<>(memberAddresses), ownerAddress, Collections.<Address>emptyList(), null, null);
    if (!mms) {
      groupDatabase.updateAvatar(groupId, avatarBytes);
      DatabaseFactory.getRecipientDatabase(context).setProfileSharing(groupRecipient, true);
      return sendGroupUpdate(context, masterSecret, groupId, memberAddresses, ownerAddress, Collections.<Address>emptySet(), name, avatarBytes, null);
    } else {
      long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient, ThreadDatabase.DistributionTypes.CONVERSATION);
      return new GroupActionResult(groupRecipient, threadId);
    }
  }

  private static Set<Address> getAdminAddresses(Context context, Collection<String> numbers)
          throws InvalidNumberException
  {
    final Set<Address> results = new HashSet<>();
    if (numbers != null) {
      for (String number : numbers) {
        results.add(Address.fromSerialized(number));
      }
    }

    return results;
  }

  private static void removeLocalRecipient(Context context, Set<Recipient> recipients) {
    String localNumber = TextSecurePreferences.getLocalNumber(context);
    for (Recipient recipient : recipients) {
      if (localNumber.equals(Util.canonicalizeNumber(context, recipient.getAddress().serialize(), recipient.getAddress().serialize()))) {
        recipients.remove(recipients.remove(recipient));
        break;
      }
    }
  }

  public static GroupActionResult updateGroup(@NonNull  Context        context,
                                              @NonNull  MasterSecret   masterSecret,
                                              @NonNull  String         groupId,
                                              @NonNull  Set<Recipient> members,
                                              @Nullable Set<String>    admins,
                                              @Nullable Bitmap         avatar,
                                              @Nullable String         name)
      throws InvalidNumberException
  {
    final GroupDatabase groupDatabase     = DatabaseFactory.getGroupDatabase(context);
    final GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroup(groupId).get();
    final Set<Address>  memberAddresses   = getMemberAddresses(members);
    final Address       ownerAddress      = groupRecord.getOwner();
    final Set<Address>  adminAddresses    = getAdminAddresses(context, admins);
    final byte[]        avatarBytes       = BitmapUtil.toByteArray(avatar);

    removeLocalRecipient(context, members);
    List<Recipient> missingMembers = groupDatabase.getGroupMembers(groupId, false);
    missingMembers.removeAll(members);

    List<Recipient> recipients = null;
    if (missingMembers.size() > 0) {
      missingMembers.addAll(members);
      recipients = missingMembers;
    }

    memberAddresses.add(Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));
    groupDatabase.updateMembers(groupId, new LinkedList<>(memberAddresses));
    groupDatabase.updateAdmins(groupId, new LinkedList<>(adminAddresses));
    groupDatabase.updateTitle(groupId, name);
    groupDatabase.updateAvatar(groupId, avatarBytes);

    if (!GroupUtil.isMmsGroup(groupId)) {
      return sendGroupUpdate(context, masterSecret, groupId, memberAddresses, ownerAddress, adminAddresses, name, avatarBytes, recipients);
    } else {
      Recipient groupRecipient = Recipient.from(context, Address.fromSerialized(groupId), true);
      long      threadId       = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
      return new GroupActionResult(groupRecipient, threadId);
    }
  }

  private static GroupActionResult sendGroupUpdate(@NonNull  Context      context,
                                                   @NonNull  MasterSecret masterSecret,
                                                   @NonNull  String       groupId,
                                                   @NonNull  Set<Address> memberAddresses,
                                                   @NonNull  Address      ownerAddress,
                                                   @NonNull  Set<Address> adminAddresses,
                                                   @Nullable String       groupName,
                                                   @Nullable byte[]       avatar,
                                                   @Nullable List<Recipient> recipients) {
    try {
      Attachment avatarAttachment = null;
      Address groupAddress        = Address.fromSerialized(groupId);
      Recipient groupRecipient    = Recipient.from(context, groupAddress, false);

      List<String> members = new LinkedList<>();

      for (Address memberAddress : memberAddresses) {
        members.add(memberAddress.serialize());
      }

      List<String> admins = new LinkedList<>();

      for (Address adminAddress : adminAddresses) {
        admins.add(adminAddress.serialize());
      }

      GroupContext.Builder groupContextBuilder = GroupContext.newBuilder()
                                                             .setId(ByteString.copyFrom(GroupUtil.getDecodedId(groupId)))
                                                             .setType(GroupContext.Type.UPDATE)
                                                             .addAllMembers(members)
                                                             .addAllAdmins(admins);
      if (groupName != null) groupContextBuilder.setName(groupName);
      if (ownerAddress != null) {
        groupContextBuilder.setOwner(ownerAddress.serialize());
      }
      GroupContext groupContext = groupContextBuilder.build();

      if (avatar != null) {
        Uri avatarUri = SingleUseBlobProvider.getInstance().createUri(avatar);
        avatarAttachment = new UriAttachment(avatarUri, MediaUtil.IMAGE_PNG, AttachmentDatabase.TRANSFER_PROGRESS_DONE, avatar.length, null, false);
      }

      OutgoingGroupMediaMessage outgoingMessage = new OutgoingGroupMediaMessage(groupRecipient, groupContext, avatarAttachment, System.currentTimeMillis(), 0);
      long threadId = MessageSender.send(context, masterSecret, outgoingMessage, -1, false, null, recipients);

      return new GroupActionResult(groupRecipient, threadId);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static Set<Address> getMemberAddresses(Collection<Recipient> recipients) {
    final Set<Address> results = new HashSet<>();
    for (Recipient recipient : recipients) {
      results.add(recipient.getAddress());
    }

    return results;
  }

  public static class GroupActionResult {
    private Recipient groupRecipient;
    private long      threadId;

    public GroupActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}
