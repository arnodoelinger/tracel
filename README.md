![Tracel](https://i.imgur.com/aKI5EBS.png)

<div align="center">
<a href="https://modrinth.com/plugin/tracel"><img src="https://i.imgur.com/C7yPsgr.png" height="48" alt="Modrinth"></a>
<a href="https://www.curseforge.com/minecraft/bukkit-plugins/tracel"><img src="https://i.imgur.com/p3OZHhH.png" height="48" alt="CurseForge"></a>
<a href="https://hangar.papermc.io/arnodoelinger/Tracel"><img src="https://i.imgur.com/EDIcQ6c.png" height="48" alt="Hangar"></a>
<a href="https://github.com/arnodoelinger/tracel"><img src="https://i.imgur.com/fd254hJ.png" height="48" alt="GitHub"></a>
<a href="https://discord.com/invite/uwMMZ2KWk6"><img src="https://i.imgur.com/g0O0JSQ.png" height="48" alt="Discord"></a>
<a href="https://ko-fi.com/arnodoelinger"><img src="https://i.imgur.com/L3Zx1Wu.png" height="48" alt="Ko-Fi"></a>
</div>

# The logger you actually need

Tracel is a logger system for Paper, Folia and their forks. It records who placed, broke, took, crafted and dropped what
— and answers "who did this?" with one click on a block.

And when it's time to roll back, Tracel doesn't just repaint blocks, but follows every item to wherever it ended up — pockets,
crafts, ender chests, death drops — and brings it home with one command!


> [!WARNING]
> Tracel is in alpha: it's safe to use, but unexpected things can happen. Keep backups of your worlds.

<img alt="Tracel" src="https://github.com/user-attachments/assets/5076e62a-6320-4c6d-9c61-07c11d33516e" />

# Choose your fighter

![Tracel: noob, pro, hacker](https://i.imgur.com/aKI5EBS.png)

No logger, CoreProtect, Tracel. We won't tell you which one to be *(be the hacker)*.

# See how exact rollbacks work

![Tracel: exact rollbacks](https://i.imgur.com/aKI5EBS.png)

Someone blows up the warehouse. By the time you log in, half the server has already grabbed the loot — "it'll get rolled back anyway, right?".

One crafts it into blocks, one hides it in an ender chest, one dies at spawn with it. A classic rollback fixes the crater, and you spend the rest of the evening digging through inventories for dupes.

With Tracel it's one command: `/tracel rollback`. Everything goes back where it came from.

Read more how rollback works [in the wiki](https://github.com/arnodoelinger/tracel/wiki)!

# Small. Fast. A little ridiculous.

![Tracel: small, fast, and just crazy](https://i.imgur.com/aKI5EBS.png)

|                                       |                                                                                                       |
|---------------------------------------|-------------------------------------------------------------------------------------------------------|
| **Database size**                     | **6–8× smaller** than CoreProtect. Same history: 367 MB vs 45 MB — and Tracel even logs a bit more    |
| **Against CoreProtect on ClickHouse** | **Tracel is still ~25% smaller** — and ClickHouse is one of the most efficient databases in the world |
| **Rollback speed**                    | **1.5–7× faster**, depending on what blew up and how much                                             |
| **Server crashed mid-rollback**       | **Zero duplicated items**, it picks up where it stopped on the next start                             |
| **Cleaning up old history**           | Deleting old history makes the database *smaller*, not [wider](https://youtube.com/link)              |

Still not convinced? [Check the benchmarks →](https://github.com/arnodoelinger/tracel/wiki)

# Everything else you'd expect

<table>
<tr>
<td valign="top" width="33%">

### When something happened

- **Inspector** — click a block, read its biography
- **Lookup** — by player, time, radius, action, item
- **"Placed ×12"** instead of twelve identical lines
- **Player report** — even for those who already logged off
- **Near** — "what just happened here?"
- **Share evidence** as an encrypted link

</td>
<td valign="top" width="33%">

### When you need it undone

- **Preview** — blinking blocks only you can see
- **Undo** — rolled back the wrong guy? Happens
- **Crash-safe** — server died mid-rollback? It just continues
- **Presets** — type `@grief`, not the same flags for the 40th time
- **Safety limits** so nobody rolls back the whole map by accident

</td>
<td valign="top" width="33%">

### For your server

- **Paper, Purpur, Folia** — 26.1 to 26.3
- **Fabric, NeoForge** — expect in 1.1 version
- **CoreProtect migrator** — bring your old history
- **WorldEdit, FAWE, Minecraft** edits get logged too
- **LuckPerms** support
- **Auto-purge** and an informative status screen
- **English & Russian** — expect more langs soon

</td>
</tr>
</table>

# Get started

Drop Tracel into `/plugins`, start the server, join as an admin. A setup wizard pops up, asks a couple of questions and
offers to bring your CoreProtect history along. That's it, you're done.

- [Install Tracel](https://github.com/arnodoelinger/tracel/wiki/%C2%A71.-Installation)
- [Coming from CoreProtect?](https://github.com/arnodoelinger/tracel/wiki/%C2%A71.-Installation#12-coming-from-coreprotect)
- [All commands and flags](https://github.com/arnodoelinger/tracel/wiki/%C2%A72.-Features)
- [The whole wiki](https://github.com/arnodoelinger/tracel/wiki)

---

<sub><img src="https://i.imgur.com/BDbw8NI.png" height="20" alt="Thanks to WHYSUMI for renders"></sub> ∙ Thanks to [@whysumi_official](https://t.me/whysumi_official) for renders.
