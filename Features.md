# Features
A full list of all features supported by Solar Patcher with a short description.  

- Metadata - Enables freelook, removes blog posts, removes pinned servers, etc: it just removes lunar bloat in general.
- Cloaks+ - Allows you to use Cloaks+ capes and cosmetics on Lunar Client < 1.8, and doesn't require a host change (you do not need their loader anymore!)
- Modpacket Removal - Prevents servers from disabling mods on Lunar Client
- Nickhider - Allows you to change your own IGN (only for yourself, can use colors)
- Support for Overlays - Re-enables Lunar Client overlays, such at those from Lunar Themes 
- Custom commands - Allows you to set aliases / create custom commands (e.g. /qb might queue bridge with the /play) command. Very similar to AutoText Hotkey, except for the "hotkey" is a command.
- Uncap reach display - Allows the user to fix the reach display when in creative mode
- RPC - Show your activity, and show "Solar Tweaks" in your Discord Rich Presence
- Custom Mods - Allows you to define custom mods, and creates some custom mods as well
- Remove Fake Levelhead - Removes the "feature" that lunar has to assign nicked players random levels in their LevelHead
- Fix Ping Sound - Fixes the chat mod "Play sound on mention" system to only respond to actual chat messages, not action bars
- NoHitDelay - Enables legacy combat, that makes sure the client doesn't prevent swing/attack packets being sent for no reason
- Window Title - Allows you to change the name of the window
- Tasklist Privacy - Prevents Lunar Client from sending all your processes to Lunar Servers
- Hostlist Privacy - Prevents Lunar Client from sending all your hosts file to Lunar Servers
- Levelhead - Change levelhead prefix
- Toggle Sprint Text - Changes the text that toggle sprint says (maybe make a deez nuts joke idk)
- FPS spoof - Multiply your FPS counter with an arbitrary value
- FPS - change FPS text
- CPS - same thing
- AutoGG - same thing but with autogg text
- Keystrokes CPS - same thing
- Reach text - same thing
- Remove hashing - Might speed up the loading of Lunar Client on low-end computers
- Debug Packets - Used by developers, helps to debug packets being sent and received from/to the Lunar BukkitAPI
- Lunar Options - Removes the Open to LAN button entirely (who uses it anyway) and replaces it with the Lunar Options button
- Toggle Sneak Container - Allows you to use togglesneak while in containers
- Ping Text & Ping Spoof - Allows you to change the text of the ping mod (might be removed in favour of the custom mods)
- ClothCapes - Enable cloth capes for everyone 
- HurtCamShake - Allows you to modify your hurt cam shake effect multiplier 
- ChatLimit - Removes the maximum chat limit count to a preset value
- MumbleFix - Can allow mumblelink on linux, but requires the user to have a special native (not recommended to use)
- Websocket - Allows you to change the lunar client asset websocket URL
- Enable Wrapped - Allows you to re-enable lunar client Wrapped, which can be useful for the people that still want to view it
- Remove Chat Delay - Removes the client-side websocket chat delay (to lunar client friends)
- Allow Cosmetic Cominations - Allows a user to use multiple incompatible cosmetics at once
- Remove Splash Blur - Removes the blur on the lunar background panorama 
- Remove Lunar Chat Cooldown - Removes the chat delay of lunar's friend chat system

#### Always enabled modules (not configurable and internal)
- RuntimeData - Internal module for looking for lunar client classes and methods
- HandleNotifications - Allows usage of LCNotificationPacket
- ModName - Changes the client brand to Solar Tweaks (version)/Lunar Client (version)
- LaunchRequestModule - Request so we can keep track of some nerdy stats (it just increments a counter)

#### Work in progress
- Replace text modules with ingame mod menu

*This list might be incomplete, check out [this code](src/main/kotlin/com/grappenmaker/solarpatcher/config/Configuration.kt) for a full list of configurable modules.*
