package com.surrealsmp.skills;

public final class SkillProgress {
    private long totalXp;

    public SkillProgress(long totalXp) {
        this.totalXp = Math.max(0L, totalXp);
    }

    public long totalXp() {
        return totalXp;
    }

    public void addXp(long amount, long cap) {
        if (amount <= 0L || totalXp >= cap) return;
        long safeAmount = Math.min(amount, cap - totalXp);
        totalXp += safeAmount;
    }
}
