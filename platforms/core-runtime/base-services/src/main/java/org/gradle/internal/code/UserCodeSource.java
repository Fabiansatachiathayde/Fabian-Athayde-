/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.code;

import org.gradle.internal.Describables;
import org.gradle.internal.DisplayName;
import org.jspecify.annotations.Nullable;

/**
 * Describes the source of code being applied.
 */
public interface UserCodeSource {
    UserCodeSource UNKNOWN = new UnknownCodeSource();
    UserCodeSource BY_RULE = new UserCodeSource() {
        @Override
        public DisplayName getDisplayName() {
            return Describables.of("By Rule");
        }

        @Override
        public @Nullable String getPluginId() {
            return null;
        }
    };

    /**
     * Returns the display name of the user code.
     */
    DisplayName getDisplayName();

    /**
     * The ID of the plugin applying user code, if available.
     */
    @Nullable
    String getPluginId();

    class UnknownCodeSource implements UserCodeSource {

        @Override
        public DisplayName getDisplayName() {
            return Describables.of("Unknown");
        }

        @Override
        public @Nullable String getPluginId() {
            return null;
        }
    }
}
