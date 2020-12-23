package com.xabber.android.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.xabber.android.R
import com.xabber.android.data.BaseIqResultUiListener
import com.xabber.android.data.SettingsManager
import com.xabber.android.data.entity.AccountJid
import com.xabber.android.data.entity.ContactJid
import com.xabber.android.data.intent.AccountIntentBuilder
import com.xabber.android.data.intent.EntityIntentBuilder
import com.xabber.android.data.message.chat.groupchat.GroupchatMemberManager
import com.xabber.android.ui.color.BarPainter
import com.xabber.android.ui.dialog.GroupchatInviteReasonDialog
import com.xabber.android.ui.dialog.GroupchatInviteReasonDialog.GroupchatInviteReasonListener
import com.xabber.android.ui.fragment.groups.GroupchatInviteContactFragment
import com.xabber.android.ui.fragment.groups.GroupchatInviteContactFragment.OnNumberOfSelectedInvitesChanged
import org.jivesoftware.smack.packet.XMPPError
import java.util.*

class GroupchatInviteContactActivity : ManagedActivity(), Toolbar.OnMenuItemClickListener, OnNumberOfSelectedInvitesChanged, BaseIqResultUiListener, GroupchatInviteReasonListener {
    private var account: AccountJid? = null
    private var groupchatContact: ContactJid? = null
    private var toolbar: Toolbar? = null
    private var barPainter: BarPainter? = null
    private var jidsToInvite: MutableList<ContactJid>? = null
    private var selectionCounter = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        account = getAccount(intent)
        groupchatContact = getGroupchatContact(intent)
        setContentView(R.layout.activity_with_toolbar_and_container)
        val lightTheme = SettingsManager.interfaceTheme() == SettingsManager.InterfaceTheme.light
        toolbar = findViewById(R.id.toolbar_default)
        toolbar.setNavigationIcon(if (lightTheme) R.drawable.ic_arrow_left_grey_24dp else R.drawable.ic_arrow_left_white_24dp)
        if (toolbar.getOverflowIcon() != null) {
            toolbar.getOverflowIcon()!!.setColorFilter(if (lightTheme) resources.getColor(R.color.grey_900) else resources.getColor(R.color.white),
                    PorterDuff.Mode.SRC_IN)
        }
        toolbar.inflateMenu(R.menu.toolbar_groupchat_list_selector)
        toolbar.setOnMenuItemClickListener(this)
        barPainter = BarPainter(this, toolbar)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().add(R.id.fragment_container,
                    GroupchatInviteContactFragment.newInstance(account, groupchatContact),
                    GroupchatInviteContactFragment.LOG_TAG).commit()
        }
    }

    override fun onResume() {
        super.onResume()
        updateToolbar()
        Toast.makeText(this, getString(R.string.groupchat_long_press_for_select_multiple_contacts), Toast.LENGTH_LONG).show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return onOptionsItemSelected(item)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_perform_on_selected) {
            openInvitationDialog()
        } else return super.onOptionsItemSelected(item)
        return true
    }

    fun openInvitationDialog() {
        jidsToInvite = inviteFragment!!.selectedContacts
        val dialog = GroupchatInviteReasonDialog()
        dialog.show(supportFragmentManager, GroupchatInviteReasonDialog.LOG_TAG)
    }

    fun openInvitationDialogForContact(contactJid: ContactJid) {
        jidsToInvite = ArrayList()
        jidsToInvite.add(contactJid)
        val dialog = GroupchatInviteReasonDialog()
        dialog.show(supportFragmentManager, GroupchatInviteReasonDialog.LOG_TAG)
    }

    private fun updateMenu() {
        onPrepareOptionsMenu(toolbar!!.menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_perform_on_selected)
                .setVisible(selectionCounter > 0).title = getString(R.string.groupchat_invite)
        return true
    }

    override fun onInviteCountChange(newCount: Int) {
        selectionCounter = newCount
        updateToolbar()
    }

    private fun updateToolbar() {
        if (selectionCounter == 0) {
            //todo change to resource
            toolbar!!.title = getString(R.string.groupchat_invite_members)
            if (SettingsManager.interfaceTheme() == SettingsManager.InterfaceTheme.light) toolbar!!.setNavigationIcon(R.drawable.ic_arrow_left_grey_24dp) else toolbar!!.setNavigationIcon(R.drawable.ic_arrow_left_white_24dp)
            barPainter!!.updateWithAccountName(account)
            toolbar!!.setNavigationOnClickListener { v: View? -> finish() }
        } else {
            toolbar!!.title = selectionCounter.toString()
            if (SettingsManager.interfaceTheme() == SettingsManager.InterfaceTheme.light) toolbar!!.setNavigationIcon(R.drawable.ic_clear_grey_24dp) else toolbar!!.setNavigationIcon(R.drawable.ic_clear_white_24dp)
            toolbar!!.setNavigationOnClickListener { v: View? ->
                val fragment = inviteFragment
                if (fragment != null) {
                    fragment.cancelSelection()
                    selectionCounter = 0
                }
                updateToolbar()
            }
        }
        updateMenu()
    }

    private val inviteFragment: GroupchatInviteContactFragment?
        private get() {
            val fragment = supportFragmentManager.findFragmentByTag(GroupchatInviteContactFragment.LOG_TAG)
            return if (fragment is GroupchatInviteContactFragment) {
                fragment
            } else null
        }

    override fun onReasonSelected(reason: String) {
        val fragment = inviteFragment
        if (fragment != null) {
            GroupchatMemberManager.getInstance().sendGroupchatInvitations(account, groupchatContact, jidsToInvite,
                    reason.trim { it <= ' ' }, this)
        }
    }

    override fun onResult() {
        //todo hiding progressbar
        finish()
    }

    override fun onSend() {
        //todo showing progressbar
    }

    override fun onIqError(error: XMPPError) {
        //todo hiding progressbar
    }

    override fun onOtherError() {
        //todo hiding progressbar
    }

    companion object {
        fun createIntent(context: Context?, account: AccountJid?, groupchatJid: ContactJid?): Intent {
            return EntityIntentBuilder(context, GroupchatInviteContactActivity::class.java)
                    .setAccount(account)
                    .setUser(groupchatJid)
                    .build()
        }

        private fun getAccount(intent: Intent): AccountJid? {
            return AccountIntentBuilder.getAccount(intent)
        }

        private fun getGroupchatContact(intent: Intent): ContactJid? {
            return EntityIntentBuilder.getUser(intent)
        }
    }
}