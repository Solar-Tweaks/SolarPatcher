/*
 * Solar Patcher, a runtime patcher for Lunar Client
 * Copyright (C) 2022 Solar Tweaks and respective contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.grappenmaker.solarpatcher.modules.mods;


import com.google.gson.Gson;
import kotlinx.serialization.json.Json;

import java.io.IOException;

public abstract class Mod {
    public Mod(ModInfo modInfo) {
        this.modInfo = modInfo;
    }

    private final ModInfo modInfo;

    public abstract void onEnable();
    public abstract void onDisable();

    public ModInfo getModInfo() {
        return modInfo;
    }
}
