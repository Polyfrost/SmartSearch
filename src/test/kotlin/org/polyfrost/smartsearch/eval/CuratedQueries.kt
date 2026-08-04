package org.polyfrost.smartsearch.eval

import org.polyfrost.oneconfig.internal.ui.search.SearchScope

/**
 * Queries like the player would phrase them
 */
object CuratedQueries {

    val queries: List<EvalQuery> = listOf(
        // Zoom
        curated("zoom level", "zoomify::behavior/basic/initial_zoom"),
        curated("zoom with scroll wheel", "zoomify::behavior/scrolling/enable_scroll_zoom"),
        curated("how fast the zoom animation is", "zoomify::behavior/basic/zoom_in_time"),

        // Brightness / gamma
        curated("brightness", "gammautils::gamma/default_gamma_percentage"),
        curated("automatic brightness in dark caves", "gammautils::gamma/dynamic_gamma.gamma/dynamic_gamma/enable_dynamic_gamma"),

        // Performance
        curated("unlock framerate in the menu", "dynamic_fps::general/misc.general/misc/uncap_menu_fps"),
        curated("how long until the game goes idle", "dynamic_fps::general/idling.general/idling/idle_timeout"),
        curated("laptop battery warnings", "dynamic_fps::general/battery_integration.general/battery_integration/battery_notifications"),

        // Chat
        curated("show time next to messages", "chattweaks.json::timestamps"),
        curated("timestamps", "chattweaks.json::timestamps"),
        curated("stack repeated chat messages", "chattweaks.json::compactChat"),
        curated("hide empty chat lines", "chattweaks.json::removeBlankMessages"),
        curated("make links in chat clickable and underlined", "chatting.json::underlinedLinks"),
        curated("make chat messages disappear after a while", "chatting.json::fade"),
        curated("round chat corners", "chatting.json::roundedChatCorners"),
        curated("scroll chat while walking", "chatting.json::chatPeek"),

        // Hypixel
        curated("say gg at the end of a game", "hytils-reborn.json::autoGG"),
        curated("requeue automatically after a game", "hytils-reborn.json::autoQueue"),
        curated("join hypixel on startup", "hytils-reborn.json::autoStart"),

        // OneConfig itself
        curated("hide all huds", "oneconfig.json::masterHudEnabled"),
        curated("key to open the settings menu", "oneconfig.json::oneConfigKeybind"),
        curated("remove a hud element", "oneconfig.json::hudDeleteKeybind"),

        // Cosmetic / animation
        curated("old cape position when sneaking", "animatium::movement/cape/1.7_cape_sneaking_position"),
        curated("cape bends smoothly instead of stiff", "waveycapes::general/text.wc.setting.capestyle"),
        curated("make my ridden horse transparent", "mountopacity::general/horse_opacity"),
        curated("3d hat layer on skin", "skinlayers3d::general/text.skinlayers.enable.hat"),

        // Crosshair
        curated("remove the crosshair entirely", "crosshairtweaks::crosshair_tweaks/general/disable_crosshair"),
        curated("hide crosshair when a chest is open", "crosshairtweaks::crosshair_tweaks/general/hide_in_containers"),
        curated("show crosshair in third person", "crosshairtweaks::crosshair_tweaks/general/show_in_third_person"),

        // Food / HUD
        curated("show saturation on the hunger bar", "appleskin::text.autoconfig.appleskin.category.default/show_saturation_overlay"),
        curated("see food values before eating", "appleskin::text.autoconfig.appleskin.category.default/show_food_values_in_tooltip"),

        // Entity culling / rendering
        curated("see nametags of players behind walls", "entityculling::general/text.entityculling.rendernametagsthroughwalls"),
        curated("turn off entity culling entirely", "entityculling::general/text.entityculling.skipentityculling"),

        // Privacy
        curated("hide my real username in game", "simplenickhider::simple_nick_hider/enable"),
        curated("what my name shows as instead", "simplenickhider::simple_nick_hider/replacement"),

        // Block outline
        curated("rainbow colored block outline", "simpleblockoverlay::outline/chroma/enable_chroma"),
        curated("thickness of the block outline", "simpleblockoverlay::outline/visual/line_width"),

        // Hotbar / items
        curated("reverse hotbar scroll direction", "scrolltweaks::scroll_tweaks/reverse_scroll_direction"),
        curated("stop scrolling from last slot back to first", "scrolltweaks::scroll_tweaks/prevent_overflow_scrolling"),
        curated("items don't bob up and down on the ground", "droppeditemtweaks::dropped_item_tweaks/static_dropped_items"),

        // World / connection
        curated("confirm before leaving a world", "confirmdisconnect::confirm_disconnect/enable_confirmation"),
        curated("only confirm quitting singleplayer worlds", "confirmdisconnect::confirm_disconnect/enable_in_singleplayer"),
        curated("go back to title screen faster while saving", "fastquit::general/render_saving_world_screen"),
        curated("join a second world without closing the first", "fastquit::mod_compatibility/allow_multiple_running_worlds"),
        curated("pause the game when the window isn't focused", "cubes-without-borders::cubes_without_borders/pause_on_lost_focus"),

        // Enchants / potions
        curated("normal numbers instead of roman numerals on enchants", "numericalenchantments::numerical_enchantments/use_arabic_numbers"),
        curated("turn off the night vision effect", "betternightvision::better_night_vision/disable_night_vision"),

        // Skybox
        curated("force stars to show with a skybox pack", "skyboxify::skyboxify_configuration/render_stars"),

        // Screens / GUI
        curated("keep cursor position switching between menus", "betterscreens::better_screens/functionality/don_t_reset_cursor"),
        curated("click outside a chest to close it", "betterscreens::better_screens/functionality/click_out_of_containers"),
        curated("remove text shadow everywhere", "sciophobia::sciophobia/hide_text_shadow"),

        // Screen shake
        curated("disable screen shake when taking damage", "shaketweaks::shake_tweaks/disable_screen_damage_tilt"),
        curated("turn off camera bobbing while walking", "shaketweaks::shake_tweaks/disable_screen_bobbing"),

        // Skyblock warp menu
        curated("replace the default warp menu with a better one", "modernwarpmenu::general/enable_modern_warp_menu"),
        curated("hide warps I can't actually use", "modernwarpmenu::general/hide_unobtainable_warps"),

        // Overlays
        curated("hide the underwater overlay", "overlaytweaks::hud/liquids/remove_water_overlay"),
        curated("stop fov changing when I go underwater", "overlaytweaks::hud/liquids/remove_submerged_fov_change"),
        curated("remove fire overlay when fire resistant", "overlaytweaks::hud/fire/remove_fire_overlay_when_resistant"),
        curated("hide the item name tooltip above hotbar", "overlaytweaks::hud/elements/remove_held_item_name_tooltip"),
        curated("keep my hand visible with hud hidden", "overlaytweaks::miscellaneous/hand/keep_hand_in_hidden_hud"),

        // Tooltips
        curated("move tooltip around with the scroll wheel", "tooltipscroll::general/enable_scrollwheel"),
        curated("move tooltips with wasd keys", "tooltipscroll::general/enable_wasd"),

        // Blur / screens
        curated("blur the screen when reading a book", "blur::blurbooks"),
        curated("blur the death screen", "blur::blurdeathscreen"),
        curated("darken the background on the title screen", "blur::darkentitlescreen"),
        curated("show current screen's id on screen", "blur::showscreenid"),

        // Skyblock / SkyOcean
        curated("show profile icon next to chat messages", "skyocean/config::skyocean/config/chat/enableprofileinchat"),
        curated("notify me when a sack fills up", "skyocean/config::skyocean/config/chat/enablesacknotification"),
        curated("fix fishing bobber rubberbanding", "skyocean/config::skyocean/config/fishing/fixbobber"),
        curated("hide other players' fishing bobbers", "skyocean/config::skyocean/config/fishing/hideotherbobbers"),

        // Skin / skybox fixes
        curated("cover black bars on alex model skins", "blackbarconcealer::text.autoconfig.blackbarconcealer.category.default/enabled"),
        curated("fix skybox clipping on low render distance", "smoothskies::smooth_skies/fix_skybox_clipping"),
        curated("remove the banding on the horizon sky", "smoothskies::smooth_skies/clear_skies"),

        // Advancements
        curated("pin advancement tabs", "paginatedadvancements::text.autoconfig.paginatedadvancements.category.default/enable_pinning_of_advancement_tabs"),
        curated("remember the last advancement tab I had open", "paginatedadvancements::text.autoconfig.paginatedadvancements.category.default/save_and_restore_the_last_selected_tab"),

        // Bug fixes (Debugify)
        curated("fix the xp bar disappearing at high levels", "debugify::client/basic_fixes/mc-79545"),
        curated("book gui isn't centered on screen", "debugify::client/basic_fixes/mc-61489"),
    )

    private fun curated(text: String, vararg relevant: String) =
        EvalQuery("curated", text, relevant.toSet(), setOf(SearchScope.Options))
}
