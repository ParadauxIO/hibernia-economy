package io.paradaux.business.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.commander.annotations.*;
import io.paradaux.hibernia.framework.commander.spi.CommandHandler;
import io.paradaux.hibernia.framework.exceptions.InternalException;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.business.model.FirmRole;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.services.FirmRoleService;
import io.paradaux.business.commands.resolvers.FirmName;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

@Singleton
@Command({"db", "democracybusiness", "business", "firm", "company"})
public class RoleCommands implements CommandHandler {

    private final FirmRoleService roles;
    private final Message message;

    @Inject
    public RoleCommands(FirmRoleService roles, Message message) {
        this.roles = roles;
        this.message = message;
    }

    @Route("staff role create <firm> <role> <order>")
    @Permission("business.staff.role.create")
    @Async
    @Description("Creates a role with default permissions")
    public void createRole(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("role") String role, @Arg("order") Integer order) {
        String firm = firmRef.value();
        UUID actor = sender.getUniqueId();
        // createRole now grants the DEFAULT permission atomically (ADT-56).
        roles.createRole(firm, role, order, actor);
        message.send(sender, "business.staff.role.create.sender", "role", role, "firm", firm);
    }

    @Route("staff role rename <firm> <role> <newname>")
    @Permission("business.staff.role.rename")
    @Async
    @Description("Rename an existing role")
    public void renameRole(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("role") String oldName, @Arg("newname") String newName) {
        String firm = firmRef.value();
        UUID actor = sender.getUniqueId();
        roles.renameRole(firm, oldName, newName, actor);
        message.send(sender, "business.staff.role.rename.sender", "oldRole", oldName, "newRole", newName, "firm", firm);
    }

    @Route("staff role delete <firm> <role>")
    @Permission("business.staff.role.delete")
    @Async
    @Description("Deletes a role and associated permissions")
    public void deleteRole(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("role") String role) {
        String firm = firmRef.value();
        UUID actor = sender.getUniqueId();
        roles.deleteRole(firm, role, actor);
        message.send(sender, "business.staff.role.delete.sender", "role", role, "firm", firm);
    }

    @Route("staff role permission add <firm> <role> <permission>")
    @Permission("business.staff.role.permission.add")
    @Async
    @Description("Add a permission to a role")
    public void addRolePermission(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("role") String role, @Arg("permission") String permission) {
        String firm = firmRef.value();
        UUID actor = sender.getUniqueId();
        roles.addRolePermission(firm, role, permission, actor);
        message.send(sender, "business.staff.role.add-permission.sender", "permission", permission, "role", role, "firm", firm);
    }

    @Route("staff role permission remove <firm> <role> <permission>")
    @Permission("business.staff.role.permission.remove")
    @Async
    @Description("Remove a permission from a role")
    public void removeRolePermission(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("role") String role, @Arg("permission") String permission) {
        String firm = firmRef.value();
        UUID actor = sender.getUniqueId();
        roles.removeRolePermission(firm, role, permission, actor);
        message.send(sender, "business.staff.role.remove-permission.sender", "permission", permission, "role", role, "firm", firm);
    }

    @Route("staff role list <firm>")
    @Permission("business.staff.role.list")
    @Async
    public void listFirmRoles(@Sender Player sender, @Arg("firm") FirmName firmRef) {
        String firm = firmRef.value();
        List<FirmRole> firmRoles = roles.getFirmRoles(firm, sender.getUniqueId());
        message.send(sender, "business.staff.role.list.header", "firm", firm);

        for (FirmRole role : firmRoles) {
            message.send(sender, "business.staff.role.list.line", "firm", firm, "role", role.getRoleName(), "ordinal", role.getRoleRankOrder());
        }
    }

    @Route("staff role permission list <firm> <role>")
    @Permission("business.staff.role.permission.list")
    @Async
    public void listFirmRolePermissions(@Sender Player sender, @Arg("firm") FirmName firmRef, @Arg("role") String role) {
        String firm = firmRef.value();
        List<FirmRolePermission> permissions = roles.getFirmRolePermissions(firm, role, sender.getUniqueId());
        message.send(sender, "business.staff.role.permission.list.header", "role", role, "firm", firm);

        for (int i = 0; i < permissions.size(); i++) {
            FirmRolePermission permission = permissions.get(i);
            message.send(sender, "business.staff.role.permission.list.line", "ordinal", i + 1, "permission", permission.getPermission().toString());
        }
    }
}