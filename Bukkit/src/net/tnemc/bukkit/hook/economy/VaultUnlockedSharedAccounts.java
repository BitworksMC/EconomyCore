package net.tnemc.bukkit.hook.economy;

/*
 * The New Economy
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

import net.milkbowl.vault2.economy.AccountPermission;
import net.tnemc.core.TNECore;
import net.tnemc.core.account.Account;
import net.tnemc.core.account.SharedAccount;
import net.tnemc.core.account.shared.Member;
import net.tnemc.core.account.shared.MemberPermissions;
import net.tnemc.plugincore.core.id.UUIDPair;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared-account operations used by both platform VaultUnlocked adapters.
 */
public final class VaultUnlockedSharedAccounts {

  private static final Map<AccountPermission, MemberPermissions> PERMISSIONS = Map.of(
          AccountPermission.DEPOSIT, MemberPermissions.DEPOSIT,
          AccountPermission.WITHDRAW, MemberPermissions.WITHDRAW,
          AccountPermission.BALANCE, MemberPermissions.BALANCE,
          AccountPermission.TRANSFER_OWNERSHIP, MemberPermissions.TRANSFER_OWNERSHIP,
          AccountPermission.INVITE_MEMBER, MemberPermissions.ADD_MEMBER,
          AccountPermission.REMOVE_MEMBER, MemberPermissions.REMOVE_MEMBER,
          AccountPermission.CHANGE_MEMBER_PERMISSION, MemberPermissions.MODIFY_MEMBER,
          AccountPermission.OWNER, MemberPermissions.OWNERSHIP,
          AccountPermission.DELETE, MemberPermissions.DELETE_ACCOUNT);

  private VaultUnlockedSharedAccounts() { }

  public static boolean create(final UUID accountID, final String name, final UUID owner) {

    if(TNECore.eco().account().findAccount(name).isPresent()
       || TNECore.eco().account().findAccount(accountID.toString()).isPresent()) {
      return false;
    }
    final SharedAccount account = new SharedAccount(accountID, name, owner);
    TNECore.eco().account().getAccounts().put(account.getIdentifier().toString(), account);
    TNECore.eco().account().uuidProvider().store(new UUIDPair(accountID, name));
    return true;
  }

  public static boolean isOwner(final UUID accountID, final UUID identifier) {

    return sharedAccount(accountID).map(account->account.getOwner().equals(identifier)).orElse(false);
  }

  public static boolean setOwner(final UUID accountID, final UUID identifier) {

    final Optional<SharedAccount> account = sharedAccount(accountID);
    if(account.isEmpty()) {
      return false;
    }
    account.get().setOwner(identifier);
    return true;
  }

  public static boolean isMember(final UUID accountID, final UUID identifier) {

    return sharedAccount(accountID)
            .map(account->account.getOwner().equals(identifier) || account.isMember(identifier))
            .orElse(false);
  }

  public static boolean addMember(final UUID accountID, final UUID identifier) {

    final Optional<SharedAccount> account = sharedAccount(accountID);
    if(account.isEmpty()) {
      return false;
    }
    account.get().getMembers().put(identifier, new Member(identifier));
    return true;
  }

  public static boolean addMember(final UUID accountID, final UUID identifier,
                                  final AccountPermission... initialPermissions) {

    final Optional<SharedAccount> account = sharedAccount(accountID);
    if(account.isEmpty()) {
      return false;
    }
    final Member member = new Member(identifier);
    for(final AccountPermission permission : initialPermissions) {
      final MemberPermissions converted = convert(permission);
      if(converted != null) {
        member.addPermission(converted, true);
      }
    }
    account.get().getMembers().put(identifier, member);
    return true;
  }

  public static boolean removeMember(final UUID accountID, final UUID identifier) {

    final Optional<SharedAccount> account = sharedAccount(accountID);
    if(account.isEmpty()) {
      return false;
    }
    account.get().getMembers().remove(identifier);
    return true;
  }

  public static boolean hasPermission(final UUID accountID, final UUID identifier,
                                      final AccountPermission permission) {

    final MemberPermissions converted = convert(permission);
    if(converted == null) {
      return false;
    }
    return sharedAccount(accountID).flatMap(account->account.findMember(identifier))
            .map(member->member.hasPermission(converted)).orElse(false);
  }

  public static boolean updatePermission(final UUID accountID, final UUID identifier,
                                         final AccountPermission permission, final boolean value) {

    final MemberPermissions converted = convert(permission);
    if(converted == null) {
      return false;
    }
    final Optional<Member> member = sharedAccount(accountID).flatMap(account->account.findMember(identifier));
    if(member.isEmpty()) {
      return false;
    }
    member.get().addPermission(converted, value);
    return true;
  }

  private static Optional<SharedAccount> sharedAccount(final UUID accountID) {

    final Optional<Account> account = TNECore.eco().account().findAccount(accountID.toString());
    return account.filter(SharedAccount.class::isInstance).map(SharedAccount.class::cast);
  }

  private static MemberPermissions convert(final AccountPermission permission) {

    return PERMISSIONS.get(permission);
  }
}
