import fs from 'node:fs';
import path from 'node:path';
const overrides = {
  "config.yml": {
    "Channels": {
      "general": "1562396102324650085"
    },
    "DiscordConsoleChannelId": "",
    "DiscordChatChannelConsoleCommandEnabled": false,
    "DiscordChatChannelListCommandExpirationDeleteRequest": false,
    "ChannelUpdater": [],
    "ChannelTopicUpdaterChannelTopicsAtShutdownEnabled": false,
    "MinecraftDiscordAccountLinkedRoleNameToAddUserTo": "",
    "MinecraftDiscordAccountLinkedConsoleCommands": [],
    "MinecraftDiscordAccountUnlinkedConsoleCommands": [],
    "MinecraftDiscordAccountLinkedUsePM": false,
    "Experiment_WebhookChatMessageDelivery": false,
    "ServerWatchdogEnabled": false
  },
  "synchronization.yml": {
    "NicknameSynchronizationEnabled": false,
    "GroupRoleSynchronizationGroupsAndRolesToSync": {},
    "GroupRoleSynchronizationOnLink": false,
    "BanSynchronizationDiscordToMinecraft": false,
    "BanSynchronizationMinecraftToDiscord": false
  },
  "voice.yml": {
    "Voice enabled": false
  },
  "messages.yml": {
    "ChannelTopicUpdaterChatChannelTopicFormat": "",
    "ChannelTopicUpdaterConsoleChannelTopicFormat": "",
    "ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat": "",
    "ChannelTopicUpdaterConsoleChannelTopicAtServerShutdownFormat": ""
  }
};
export function enforceDiscordStaging(root, checkOnly) {
  if (!process.env.DISCORDSRV_STAGING_TOKEN) throw new Error('Missing Infisical DISCORDSRV_STAGING_TOKEN');
  for (const [name, settings] of Object.entries(overrides)) {
    const file = path.join(root, 'plugins/DiscordSRV', name);
    let text = fs.readFileSync(file, 'utf8');
    const original = text;
    for (const [key, value] of Object.entries(settings)) {
      const lines = text.split('\n');
      const matches = lines.flatMap((line, i) => line.startsWith(key + ':') ? [i] : []);
      if (matches.length !== 1) throw new Error('Staging Discord setting missing or duplicated: ' + key);
      const start = matches[0];
      let end = start + 1;
      while (end < lines.length && !/^[A-Za-z][A-Za-z0-9 _-]*:/.test(lines[end])) end++;
      const comments = lines.slice(start + 1, end).filter(line => !line.trim() || line.trimStart().startsWith('#'));
      const replacement = [key + ': ' + JSON.stringify(value), ...comments];
      if (checkOnly && lines.slice(start, end).join('\n') !== replacement.join('\n')) throw new Error('Unsafe staging Discord setting: ' + key);
      lines.splice(start, end - start, ...replacement);
      text = lines.join('\n');
    }
    if (!checkOnly && text !== original) fs.writeFileSync(file, text);
  }
  // PostmanPat's punishment listeners can consult this map even with its verification module disabled.
  // Keep the copied production mappings out of staging; the original is backed up in ADT-193.
  const postmanVerificationFile = path.join(root, 'plugins/PostmanPat/verificationStorage.conf');
  if (fs.existsSync(postmanVerificationFile)) {
    const content = fs.readFileSync(postmanVerificationFile, 'utf8');
    if (!/^\s*guild-role-map\s*\{\s*\}\s*$/.test(content)) {
      if (checkOnly) throw new Error('Unsafe PostmanPat guild-role mappings in staging');
      fs.writeFileSync(postmanVerificationFile, 'guild-role-map {}\n');
    }
  }

}
