package com.brandsmith.api.battle;

import java.util.List;

public record BattleResult(List<Position> positions, JudgeResult judge, Integer selected) {
}
