// $Id$
/*
 * CommandBook
 * Copyright (C) 2010, 2011 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

package com.sk89q.commandbook;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import com.sk89q.commandbook.components.ComponentManager;
import com.sk89q.commandbook.components.ConfigListedComponentLoader;
import com.sk89q.commandbook.events.core.EventManager;
import com.sk89q.commandbook.util.CommandRegistration;
import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.wepif.PermissionsResolverManager;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.player.PlayerListener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.config.Configuration;
import com.sk89q.commandbook.commands.*;
import com.sk89q.commandbook.locations.FlatFileLocationsManager;
import com.sk89q.commandbook.locations.LocationManager;
import com.sk89q.commandbook.locations.LocationManagerFactory;
import com.sk89q.commandbook.locations.RootLocationManager;
import com.sk89q.commandbook.locations.NamedLocation;
import com.sk89q.minecraft.util.commands.*;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.blocks.ClothColor;
import com.sk89q.worldedit.blocks.ItemType;
import static com.sk89q.commandbook.CommandBookUtil.*;
import com.sk89q.commandbook.locations.WrappedSpawnManager;
import static com.sk89q.commandbook.util.PlayerUtil.*;

/**
 * Base plugin class for CommandBook.
 * 
 * @author sk89q
 */
@SuppressWarnings("deprecation")
public final class CommandBook extends JavaPlugin {
    
    private static final Logger logger = Logger.getLogger("Minecraft.CommandBook");
    
    private static CommandBook instance;

    private CommandsManager<CommandSender> commands;
    
    public boolean listOnJoin;
    public boolean verifyNameFormat;
    public boolean broadcastChanges;
    public boolean broadcastKicks;
    public boolean broadcastBans;
    public Set<Integer> thorItems;
    public boolean opPermissions;
    public boolean useDisplayNames;

    public boolean playersListColoredNames;
    public boolean playersListGroupedNames;
    public boolean playersListMaxPlayers;
    public boolean crappyWrapperCompat;

    protected Map<String, String> messages = new HashMap<String, String>();
    protected YAMLProcessor config;
    protected EventManager eventManager = new EventManager();
    protected ComponentManager componentManager;

    
    public CommandBook() {
        super();
        instance = this;
    }
    
    public static CommandBook inst() {
        return instance;
    }
    
    public static Server server() {
        return Bukkit.getServer();
    }
    
    public static Logger logger() {
        return logger;
    }

    /**
     * Called when the plugin is enabled. This is where configuration is loaded,
     * and the plugin is setup.
     */
    public void onEnable() {
        logger.info(getDescription().getName() + " "
                + getDescription().getVersion() + " enabled.");
        
        // Make the data folder for the plugin where configuration files
        // and other data files will be stored
        getDataFolder().mkdirs();

        createDefaultConfiguration("config.yml");
        createDefaultConfiguration("kits.txt");

        // Register the commands that we want to use
        final CommandBook plugin = this;
        commands = new CommandsManager<CommandSender>() {
            @Override
            public boolean hasPermission(CommandSender player, String perm) {
                return plugin.hasPermission(player, perm);
            }
        };


        commands.setInjector(new Injector() {
            public Object getInstance(Class<?> cls) throws InvocationTargetException,
                    IllegalAccessException, InstantiationException {
                Constructor<?> constr;
                try {
                    constr = cls.getConstructor(CommandBook.class);
                } catch (SecurityException e) {
                    e.printStackTrace();
                    return null;
                } catch (NoSuchMethodException e) {
                    e.printStackTrace();
                    return null;
                }
                return constr.newInstance(plugin);
            }
        });

        // Prepare permissions
        PermissionsResolverManager.initialize(this);
        
        componentManager = new ComponentManager();
        componentManager.addComponentLoader(new ConfigListedComponentLoader());

        componentManager.loadComponents();
        
        // Load configuration
        populateConfiguration();
        
		final CommandRegistration cmdRegister = new CommandRegistration(this, commands);
        cmdRegister.register(GeneralCommands.class);
		cmdRegister.register(FunCommands.class);
        cmdRegister.register(WorldCommands.class);
        
        // Register events
        registerEvents();

        componentManager.enableComponents();
    }
    
    /**
     * Register the events that are used.
     */
    protected void registerEvents() {
        PlayerListener playerListener = new CommandBookPlayerListener(this);

        registerEvent(Event.Type.PLAYER_LOGIN, playerListener);
        registerEvent(Event.Type.PLAYER_JOIN, playerListener);
        registerEvent(Event.Type.PLAYER_INTERACT, playerListener);
    }

    /**
     * Called when the plugin is disabled. Shutdown and clearing of any
     * temporary data occurs here.
     */
    public void onDisable() {
        this.getServer().getScheduler().cancelTasks(this);
        componentManager.unloadComponents();
    }
    
    /**
     * Called on a command.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
            String commandLabel, String[] args) {
        try {
            commands.execute(cmd.getName(), args, sender, sender);
        } catch (CommandPermissionsException e) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
        } catch (MissingNestedCommandException e) {
            sender.sendMessage(ChatColor.RED + e.getUsage());
        } catch (CommandUsageException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
            sender.sendMessage(ChatColor.RED + e.getUsage());
        } catch (WrappedCommandException e) {
            if (e.getCause() instanceof NumberFormatException) {
                sender.sendMessage(ChatColor.RED + "Number expected, string received instead.");
            } else {
                sender.sendMessage(ChatColor.RED + "An error has occurred. See console.");
                e.printStackTrace();
            }
        } catch (CommandException e) {
            sender.sendMessage(ChatColor.RED + e.getMessage());
        }
        
        return true;
    }
    
    /**
     * Register an event.
     * 
     * @param type
     * @param listener
     * @param priority
     */
    protected void registerEvent(Event.Type type, Listener listener, Priority priority) {
        getServer().getPluginManager()
                .registerEvent(type, listener, priority, this);
    }
    
    /**
     * Register an event at normal priority.
     * 
     * @param type
     * @param listener
     */
    protected void registerEvent(Event.Type type, Listener listener) {
        getServer().getPluginManager()
                .registerEvent(type, listener, Priority.Normal, this);
    }
    
    /**
     * Loads the configuration.
     */
    @SuppressWarnings({ "unchecked" })
    public void populateConfiguration() {
        YAMLProcessor config = new YAMLProcessor(new File(getDataFolder(), "config.yml"), true, YAMLFormat.EXTENDED);
        try {
            config.load();
        } catch (IOException e) {
            logger.log(Level.WARNING, "CommandBook: Error loading configuration: ", e);
        }
        this.config = config;
        
        // Load messages
        messages.put("motd", config.getString("motd", null));
        messages.put("rules", config.getString("rules", null));

        playersListColoredNames = config.getBoolean("online-list.colored-names", false);
        playersListGroupedNames = config.getBoolean("online-list.grouped-names", false);
        playersListMaxPlayers = config.getBoolean("online-list.show-max-players", true);
        
        listOnJoin = getConfiguration().getBoolean("online-on-join", true);
        opPermissions = config.getBoolean("op-permissions", true);
        useDisplayNames = config.getBoolean("use-display-names", true);
        verifyNameFormat = config.getBoolean("verify-name-format", true);
        broadcastChanges = config.getBoolean("broadcast-changes", true);
        broadcastBans = config.getBoolean("broadcast-bans", false);
        broadcastKicks = config.getBoolean("broadcast-kicks", false);
        crappyWrapperCompat = config.getBoolean("crappy-wrapper-compat", true);
        thorItems = new HashSet<Integer>(config.getIntList(
                "thor-hammer-items", Arrays.asList(278, 285, 257, 270)));
        
        if (crappyWrapperCompat) {
            logger.info("CommandBook: Maximum wrapper compatibility is enabled. " +
                    "Some features have been disabled to be compatible with " +
                    "poorly written server wrappers.");
        }

    }
    
    /**
     * Create a default configuration file from the .jar.
     * 
     * @param name
     */
    protected void createDefaultConfiguration(String name) {
        File actual = new File(getDataFolder(), name);
        if (!actual.exists()) {

            InputStream input = null;
            try {
                JarFile file = new JarFile(getFile());
                ZipEntry copy = file.getEntry("defaults/" + name);
                if (copy == null) throw new FileNotFoundException();
                input = file.getInputStream(copy);
            } catch (IOException e) {
                logger.severe(getDescription().getName() + ": Unable to read default configuration: " + name);
            }
            if (input != null) {
                FileOutputStream output = null;

                try {
                    output = new FileOutputStream(actual);
                    byte[] buf = new byte[8192];
                    int length = 0;
                    while ((length = input.read(buf)) > 0) {
                        output.write(buf, 0, length);
                    }
                    
                    logger.info(getDescription().getName()
                            + ": Default configuration file written: " + name);
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (input != null)
                            input.close();
                    } catch (IOException e) {}

                    try {
                        if (output != null)
                            output.close();
                    } catch (IOException e) {}
                }
            }
        }
    }
    
    /**
     * Checks permissions.
     * 
     * @param sender
     * @param perm
     * @return 
     */
    public boolean hasPermission(CommandSender sender, String perm) {
        if (!(sender instanceof Player)) {
            return ((sender.isOp() && (opPermissions || sender instanceof ConsoleCommandSender)) 
                    || PermissionsResolverManager.getInstance().hasPermission(sender.getName(), perm));
        } 
        return hasPermission(sender, ((Player) sender).getWorld(), perm);
    }

    public boolean hasPermission(CommandSender sender, World world, String perm) {
        if ((sender.isOp() && opPermissions) || sender instanceof ConsoleCommandSender) {
            return true;
        }

        // Invoke the permissions resolver
        if (sender instanceof Player) {
            Player player = (Player) sender;
            return PermissionsResolverManager.getInstance().hasPermission(world.getName(), player.getName(), perm);
        }

        return false;
    }

    /**
     * Checks permissions and throws an exception if permission is not met.
     * 
     * @param sender
     * @param perm
     * @throws CommandPermissionsException 
     */
    public void checkPermission(CommandSender sender, String perm)
            throws CommandPermissionsException {
        if (!hasPermission(sender, perm)) {
            throw new CommandPermissionsException();
        }
    }
    
    public void checkPermission(CommandSender sender, World world, String perm)
            throws CommandPermissionsException {
        if (!hasPermission(sender, world, perm)) {
            throw new CommandPermissionsException();
        }
    }
    
    /**
     * Attempts to match a creature type.
     * 
     * @param sender
     * @param filter
     * @return
     * @throws CommandException
     */
    public CreatureType matchCreatureType(CommandSender sender,
            String filter) throws CommandException {
        CreatureType type = CreatureType.fromName(filter);
        if (type != null) {
            return type;
        }
        
        for (CreatureType testType : CreatureType.values()) {
            if (testType.getName().toLowerCase().startsWith(filter.toLowerCase())) {
                return testType;
            }
        }

        throw new CommandException("Unknown mob specified! You can "
                + "choose from the list of: "
                + CommandBookUtil.getCreatureTypeNameList());
    }
    
    /**
     * Gets the IP address of a command sender.
     * 
     * @param sender
     * @return
     */
    public String toInetAddressString(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getAddress().getAddress().getHostAddress();
        } else {
            return "127.0.0.1";
        }
    }
    
    /**
     * Gets the name of a command sender. This is a unique name and this
     * method should never return a "display name".
     * 
     * @param sender
     * @return
     */
    public String toUniqueName(CommandSender sender) {
        if (sender instanceof Player) {
            return ((Player) sender).getName();
        } else {
            return "*Console*";
        }
    }

    /**
     * Get preprogrammed messages.
     * 
     * @param id 
     * @return may return null
     */
    public String getMessage(String id) {
        return messages.get(id);
    }
    
    /**
     * Replace macros in the text.
     * 
     * @param sender 
     * @param message
     * @return
     */
    public String replaceMacros(CommandSender sender, String message) {
        Player[] online = getServer().getOnlinePlayers();
        
        message = message.replace("%name%", toName(sender));
        message = message.replace("%cname%", toColoredName(sender, null));
        message = message.replace("%id%", toUniqueName(sender));
        message = message.replace("%online%", String.valueOf(online.length));
        
        // Don't want to build the list unless we need to
        if (message.contains("%players%")) {
            message = message.replace("%players%",
                    CommandBookUtil.getOnlineList(online));
        }
        
        if (sender instanceof Player) {
            Player player = (Player) sender;
            World world = player.getWorld();

            message = message.replace("%time%", getTimeString(world.getTime()));
            message = message.replace("%world%", world.getName());
        }
        
        Pattern cmdPattern = Pattern.compile("%cmd:([^%]+)%");
        Matcher matcher = cmdPattern.matcher(message);
        try {
            StringBuffer buff = new StringBuffer();
            while (matcher.find()) {
                Process p = new ProcessBuilder(matcher.group(1).split(" ")).start();
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String s;
                StringBuilder build = new StringBuilder();
                while ((s = stdInput.readLine()) != null) {
                    build.append(s + " ");
                }
                stdInput.close();
                build.delete(build.length() - 1, build.length());
                matcher.appendReplacement(buff, build.toString());
                p.destroy();
            }
            matcher.appendTail(buff);
            message = buff.toString();
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Error replacing macros: " + e.getMessage());
        }
        return message;
    }

    /**
     * Get the permissions resolver.
     * 
     * @return
     */
    public PermissionsResolverManager getPermissionsResolver() {
        return PermissionsResolverManager.getInstance();
    }

    public YAMLProcessor getGlobalConfiguration() {
        return config;
    }
    
    public EventManager getEventManager() {
        return eventManager;
    }

    public ComponentManager getComponentManager() {
        return componentManager;
    }
}
