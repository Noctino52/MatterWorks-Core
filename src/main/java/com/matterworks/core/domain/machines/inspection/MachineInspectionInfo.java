package com.matterworks.core.domain.machines.inspection;

import com.matterworks.core.common.GridPosition;
import com.matterworks.core.domain.machines.base.MachineWorkState;

import java.util.List;

/**
 * Snapshot read-only per UI/Hytale: informazioni standard per infobox.
 */
public record MachineInspectionInfo(
        String machineName,
        String typeId,
        GridPosition anchorPos,
        MachineWorkState state,

        int totalMatterCount,
        int totalColorCount,

        List<String> inputLines,
        List<String> outputLines,
        List<String> targetOutputLines,

        boolean showInUi
) { }
