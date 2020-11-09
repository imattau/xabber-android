package com.xabber.android.data.message.chat.groupchat;

import android.content.Context;
import android.widget.Toast;

import com.xabber.android.R;
import com.xabber.android.data.Application;
import com.xabber.android.data.account.AccountManager;
import com.xabber.android.data.connection.ConnectionItem;
import com.xabber.android.data.connection.listeners.OnPacketListener;
import com.xabber.android.data.database.realmobjects.MessageRealmObject;
import com.xabber.android.data.database.repositories.MessageRepository;
import com.xabber.android.data.entity.AccountJid;
import com.xabber.android.data.entity.ContactJid;
import com.xabber.android.data.extension.groupchat.GroupPinMessageIQ;
import com.xabber.android.data.extension.groupchat.GroupchatExtensionElement;
import com.xabber.android.data.extension.groupchat.GroupchatPresence;
import com.xabber.android.data.extension.groupchat.create.CreateGroupchatIQ;
import com.xabber.android.data.extension.groupchat.create.CreateGroupchatIqResultListener;
import com.xabber.android.data.extension.groupchat.settings.GroupSettingsDataFormResultIQ;
import com.xabber.android.data.extension.groupchat.settings.GroupSettingsRequestFormQueryIQ;
import com.xabber.android.data.extension.groupchat.settings.GroupSettingsResultsListener;
import com.xabber.android.data.extension.groupchat.settings.SetGroupSettingsRequestIQ;
import com.xabber.android.data.extension.groupchat.status.GroupSetStatusRequestIQ;
import com.xabber.android.data.extension.groupchat.status.GroupStatusDataFormIQ;
import com.xabber.android.data.extension.groupchat.status.GroupStatusFormRequestIQ;
import com.xabber.android.data.extension.groupchat.status.GroupStatusResultListener;
import com.xabber.android.data.extension.mam.NextMamManager;
import com.xabber.android.data.log.LogManager;
import com.xabber.android.data.message.chat.ChatManager;
import com.xabber.android.data.message.chat.RegularChat;
import com.xabber.android.data.roster.PresenceManager;
import com.xabber.xmpp.sid.UniqStanzaHelper;
import com.xabber.xmpp.smack.XMPPTCPConnection;

import org.greenrobot.eventbus.EventBus;
import org.jivesoftware.smack.XMPPException;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.StandardExtensionElement;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.util.PacketParserUtils;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.disco.packet.DiscoverItems;
import org.jivesoftware.smackx.xdata.packet.DataForm;
import org.jxmpp.jid.Jid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroupchatManager implements OnPacketListener {

    public static final String NAMESPACE = "https://xabber.com/protocol/groups";
    public static final String SYSTEM_MESSAGE_NAMESPACE = NAMESPACE + "#system-message";
    private static final String LOG_TAG = GroupchatManager.class.getSimpleName();
    private static GroupchatManager instance;

    /* */
    private final Map<AccountJid, List<Jid>> availableGroupchatServers = new HashMap<>();

    public static GroupchatManager getInstance() {
        if (instance == null)
            instance = new GroupchatManager();
        return instance;
    }

    @Override
    public void onStanza(ConnectionItem connection, Stanza packet) {
        if (packet instanceof Presence && packet.hasExtension(GroupchatPresence.NAMESPACE)) {
            processPresence(connection, packet);
        } else if (packet instanceof Message
                && ((Message) packet).getType().equals(Message.Type.headline)
                && packet.hasExtension(GroupchatExtensionElement.ELEMENT, SYSTEM_MESSAGE_NAMESPACE)) {
            processHeadlineEchoMessage(connection, packet);
        } else if (packet instanceof DiscoverItems) {
            processDiscoInfoIq(connection, packet);
        }
    }

    private void processDiscoInfoIq(ConnectionItem connectionItem, Stanza packet) {
        try {
            AccountJid accountJid = connectionItem.getAccount();

            if (availableGroupchatServers.get(accountJid) == null)
                availableGroupchatServers.remove(accountJid);

            availableGroupchatServers.put(accountJid, new ArrayList<>());

            for (DiscoverItems.Item item : ((DiscoverItems) packet).getItems()) {
                if (NAMESPACE.equals(item.getNode()))
                    availableGroupchatServers.get(accountJid).add(ContactJid.from(item.getEntityID()).getBareJid());
            }
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
        }
    }

    private void processHeadlineEchoMessage(ConnectionItem connectionItem, Stanza packet) {
        try {
            //if groupchat headlines aren't correctly parsed, must rewrite this
            StandardExtensionElement echoElement = (StandardExtensionElement) packet.getExtensions().get(0);
            Message message = PacketParserUtils.parseStanza(echoElement.getElements().get(0).toXML().toString());
            String originId = UniqStanzaHelper.getOriginId(message);
            String stanzaId = UniqStanzaHelper.getContactStanzaId(message);
            MessageRepository.setStanzaIdByOriginId(originId, stanzaId);
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
        }
    }

    private void processPresence(ConnectionItem connection, Stanza packet) {
        try {
            GroupchatPresence presence = (GroupchatPresence) packet.getExtension(GroupchatPresence.NAMESPACE);

            AccountJid accountJid = AccountJid.from(packet.getTo().toString());
            ContactJid contactJid = ContactJid.from(packet.getFrom());

            if (ChatManager.getInstance().getChat(accountJid, contactJid) instanceof RegularChat) {
                ChatManager.getInstance().removeChat(accountJid, contactJid);
                ChatManager.getInstance().createGroupChat(accountJid, contactJid);
            }

            GroupChat groupChat = (GroupChat) ChatManager.getInstance().getChat(accountJid, contactJid);

            if (groupChat == null) {
                LogManager.e(LOG_TAG, "Got the groupchat presence, but groupchat isn't exist yet");
                return;
            }

            if (presence.getPinnedMessageId() != null
                    && !presence.getPinnedMessageId().isEmpty()
                    && !presence.getPinnedMessageId().equals("0")) {
                MessageRealmObject pinnedMessage = MessageRepository
                        .getMessageFromRealmByStanzaId(presence.getPinnedMessageId());
                if (pinnedMessage == null || pinnedMessage.getTimestamp() == null) {

                    NextMamManager.getInstance().requestSingleMessageAsync(connection,
                            groupChat, presence.getPinnedMessageId());
                } else groupChat.setPinnedMessageId(presence.getPinnedMessageId());
            }

            groupChat.setDescription(presence.getDescription());
            groupChat.setName(presence.getName());
            groupChat.setIndexType(presence.getIndex());
            groupChat.setPrivacyType(presence.getPrivacy());
            groupChat.setMembershipType(presence.getMembership());
            groupChat.setNumberOfMembers(presence.getAllMembers());
            groupChat.setNumberOfOnlineMembers(presence.getPresentMembers());

            EventBus.getDefault().post(new GroupchatPresenceUpdatedEvent(accountJid, contactJid));
            //todo etc...
            ChatManager.getInstance().saveOrUpdateChatDataToRealm(groupChat);
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
        }
    }

    public boolean isSupported(XMPPTCPConnection connection) {
        try {
            return ServiceDiscoveryManager.getInstanceFor(connection)
                    .serverSupportsFeature(NAMESPACE);
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
            return false;
        }
    }

    public boolean isSupported(AccountJid accountJid) {
        return isSupported(AccountManager.getInstance().getAccount(accountJid).getConnection());
    }

    public void sendCreateGroupchatRequest(AccountJid accountJid, String server, String groupName,
                                           String description, String localpart,
                                           GroupchatMembershipType membershipType,
                                           GroupchatIndexType indexType,
                                           GroupchatPrivacyType privacyType,
                                           CreateGroupchatIqResultListener listener) {
        CreateGroupchatIQ iq = new CreateGroupchatIQ(accountJid.getFullJid(),
                server, groupName, localpart, description, membershipType, privacyType, indexType);

        try {
            AccountManager.getInstance().getAccount(accountJid).getConnection()
                    .sendIqWithResponseCallback(iq, packet -> {
                        if (packet instanceof IQ && ((IQ) packet).getType() == IQ.Type.result) {
                            LogManager.d(LOG_TAG, "Groupchat successfully created");

                            if (packet instanceof CreateGroupchatIQ.ResultIq) {
                                try {
                                    ContactJid contactJid = ContactJid.from(((CreateGroupchatIQ.ResultIq) packet).getJid());
                                    AccountJid account = AccountJid.from(packet.getTo().toString());
                                    PresenceManager.getInstance().addAutoAcceptSubscription(account, contactJid);
                                    PresenceManager.getInstance().acceptSubscription(account, contactJid, true);
                                    PresenceManager.getInstance().requestSubscription(account, contactJid);
                                    listener.onSuccessfullyCreated(accountJid, contactJid);
                                } catch (Exception e) {
                                    LogManager.exception(LOG_TAG, e);
                                    listener.onOtherError();
                                }
                            }

                        }
                    }, exception -> {
                        LogManager.exception(LOG_TAG, exception);
                        if (exception instanceof XMPPException.XMPPErrorException
                                && ((XMPPException.XMPPErrorException) exception).getXMPPError().getStanza().toXML().toString().contains("conflict")) {
                            listener.onJidConflict();
                        } else listener.onOtherError();
                    });
        } catch (Exception e) {
            LogManager.exception(LOG_TAG, e);
        }

    }

    public List<Jid> getAvailableGroupchatServersForAccountJid(AccountJid accountJid) {
        return availableGroupchatServers.get(accountJid);
    }

    public void requestGroupStatusForm(GroupChat groupchat) {
        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            try {
                AccountManager.getInstance().getAccount(groupchat.getAccount()).getConnection()
                        .sendIqWithResponseCallback(new GroupStatusFormRequestIQ(groupchat.getContactJid()), packet -> {
                            if (packet instanceof GroupStatusDataFormIQ
                                    && ((GroupStatusDataFormIQ) packet).getType() == IQ.Type.result)
                                for (GroupStatusResultListener listener : Application.getInstance().getUIListeners(GroupStatusResultListener.class)) {
                                    listener.onStatusDataFormReceived(groupchat, ((GroupStatusDataFormIQ) packet).getDataForm());
                                }
                        }, exception -> {
                            for (GroupStatusResultListener listener : Application.getInstance().getUIListeners(GroupStatusResultListener.class)) {
                                listener.onError(groupchat);
                            }
                        });

            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
                for (GroupStatusResultListener listener : Application.getInstance().getUIListeners(GroupStatusResultListener.class)) {
                    listener.onError(groupchat);
                }
            }
        });
    }

    public void sendSetGroupchatStatusRequest(GroupChat groupChat, DataForm dataForm) {
        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            try {
                AccountManager.getInstance().getAccount(groupChat.getAccount()).getConnection()
                        .sendIqWithResponseCallback(new GroupSetStatusRequestIQ(groupChat.getContactJid(), dataForm), packet -> {
                            if (packet instanceof IQ && ((IQ) packet).getType() == IQ.Type.result)
                                for (GroupStatusResultListener listener : Application.getInstance().getUIListeners(GroupStatusResultListener.class)) {
                                    listener.onStatusSuccessfullyChanged(groupChat);
                                }
                        }, exception -> {
                            for (GroupStatusResultListener listener : Application.getInstance().getUIListeners(GroupStatusResultListener.class)) {
                                listener.onError(groupChat);
                            }
                        });

            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
                for (GroupStatusResultListener listener : Application.getInstance().getUIListeners(GroupStatusResultListener.class)) {
                    listener.onError(groupChat);
                }
            }
        });
    }

    public void requestGroupSettingsForm(GroupChat groupchat) {
        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            try {
                AccountManager.getInstance().getAccount(groupchat.getAccount()).getConnection()
                        .sendIqWithResponseCallback(new GroupSettingsRequestFormQueryIQ(groupchat.getContactJid()), packet -> {
                            if (packet instanceof GroupSettingsDataFormResultIQ
                                    && ((GroupSettingsDataFormResultIQ) packet).getType() == IQ.Type.result)
                                for (GroupSettingsResultsListener listener : Application.getInstance().getUIListeners(GroupSettingsResultsListener.class)) {
                                    listener.onDataFormReceived(groupchat, ((GroupSettingsDataFormResultIQ) packet).getDataFrom());
                                }
                        }, exception -> {
                            for (GroupSettingsResultsListener listener : Application.getInstance().getUIListeners(GroupSettingsResultsListener.class)) {
                                listener.onErrorAtDataFormRequesting(groupchat);
                            }
                        });

            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
                for (GroupSettingsResultsListener listener : Application.getInstance().getUIListeners(GroupSettingsResultsListener.class)) {
                    listener.onErrorAtDataFormRequesting(groupchat);
                }
            }
        });
    }

    public void sendSetGroupSettingsRequest(GroupChat groupchat, DataForm dataForm) {
        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            try {
                AccountManager.getInstance().getAccount(groupchat.getAccount()).getConnection()
                        .sendIqWithResponseCallback(new SetGroupSettingsRequestIQ(groupchat.getContactJid(), dataForm), packet -> {
                            if (packet instanceof IQ && ((IQ) packet).getType() == IQ.Type.result)
                                for (GroupSettingsResultsListener listener : Application.getInstance().getUIListeners(GroupSettingsResultsListener.class)) {
                                    listener.onGroupSettingsSuccessfullyChanged(groupchat);
                                }
                        }, exception -> {
                            for (GroupSettingsResultsListener listener : Application.getInstance().getUIListeners(GroupSettingsResultsListener.class)) {
                                listener.onErrorAtSettingsSetting(groupchat);
                            }
                        });

            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
                for (GroupSettingsResultsListener listener : Application.getInstance().getUIListeners(GroupSettingsResultsListener.class)) {
                    listener.onErrorAtSettingsSetting(groupchat);
                }
            }
        });
    }

    public void sendUnPinMessageRequest(GroupChat groupChat) {
        //todo add privilege checking

        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {
            try {

                GroupPinMessageIQ iq = new GroupPinMessageIQ(groupChat.getAccount().getFullJid(),
                        groupChat.getContactJid().getJid(), "");

                AccountManager.getInstance().getAccount(groupChat.getAccount()).getConnection()
                        .sendIqWithResponseCallback(iq, packet -> {
                            if (packet instanceof IQ && ((IQ) packet).getType().equals(IQ.Type.result))
                                LogManager.d(LOG_TAG, "Message successfully unpinned");
                        }, exception -> {
                            LogManager.exception(LOG_TAG, exception);
                            Context context = Application.getInstance().getApplicationContext();
                            Application.getInstance().runOnUiThread(() ->
                                    Toast.makeText(context,
                                            context.getText(R.string.groupchat_failed_to_unpin_message),
                                            Toast.LENGTH_SHORT).show());
                        });
            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
            }
        });
    }

    public void sendPinMessageRequest(MessageRealmObject message) {
        //todo add privilege checking

        final AccountJid account = message.getAccount();
        final Jid contact = message.getUser().getJid();

        Application.getInstance().runInBackgroundNetworkUserRequest(() -> {

            try {

                GroupPinMessageIQ iq = new GroupPinMessageIQ(account.getFullJid(), contact,
                        message.getStanzaId());

                AccountManager.getInstance().getAccount(account).getConnection()
                        .sendIqWithResponseCallback(iq, packet -> {
                            if (packet instanceof IQ && ((IQ) packet).getType().equals(IQ.Type.result))
                                LogManager.d(LOG_TAG, "Message successfully pinned");
                        }, exception -> {
                            LogManager.d(LOG_TAG, "Failed to pin message");
                            Context context = Application.getInstance().getApplicationContext();
                            Application.getInstance().runOnUiThread(() ->
                                    Toast.makeText(context,
                                            context.getText(R.string.groupchat_failed_to_pin_message),
                                            Toast.LENGTH_SHORT).show());
                        });
            } catch (Exception e) {
                LogManager.exception(LOG_TAG, e);
            }
        });
    }

    public static class GroupchatPresenceUpdatedEvent {
        private final AccountJid account;
        private final ContactJid groupJid;

        GroupchatPresenceUpdatedEvent(AccountJid account, ContactJid groupchatJid) {
            this.account = account;
            this.groupJid = groupchatJid;
        }

        public AccountJid getAccount() {
            return account;
        }

        public ContactJid getGroupJid() {
            return groupJid;
        }
    }

}
