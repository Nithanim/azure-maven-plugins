/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.common.action;

import com.microsoft.azure.toolkit.lib.common.view.IView;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

@Getter
public class ActionGroup implements IActionGroup {
    @Nullable
    private IView.Label view;
    private final List<Object> actions;
    @Setter
    private Object origin; // ide's action group.

    public ActionGroup(@Nonnull List<Object> actions) {
        this.actions = actions;
    }

    public ActionGroup(@Nonnull Object... actions) {
        this.actions = Arrays.asList(actions);
    }

    public ActionGroup(@Nonnull List<Object> actions, @Nullable IView.Label view) {
        this.view = view;
        this.actions = actions;
    }

    public void addAction(Object action) {
        this.actions.add(action);
    }
}
