package client

import (
	"context"
	"testing"
)

// ai-generated: the whole file (review of #20). The recovery owner's rules,
// each one a line something else in the client leans on: a released owner
// lets its context go, a stale owner touches nothing, and a caller that is
// not the owner waits its turn instead of cancelling the one that runs.

func TestRecoveryReleaseEndsTheOwnersRun(t *testing.T) {
	var r recovery
	run, gen := r.take(context.Background())
	r.release(gen)
	select {
	case <-run.Done():
	default:
		t.Fatal("release left the owner's run open: every recovery leaks one")
	}
}

func TestRecoveryStaleReleaseLeavesTheLiveOwnerAlone(t *testing.T) {
	var r recovery
	_, old := r.take(context.Background())
	run, gen := r.take(context.Background())
	r.release(old)
	select {
	case <-run.Done():
		t.Fatal("a released owner from before ended the run of the one that took over")
	default:
	}
	r.release(gen)
}

func TestRecoveryTakeEndsTheOwnerItTakesOverFrom(t *testing.T) {
	var r recovery
	first, _ := r.take(context.Background())
	_, gen := r.take(context.Background())
	select {
	case <-first.Done():
	default:
		t.Fatal("take left the previous owner running")
	}
	r.release(gen)
}

// takeIf at the owner's own generation: the owner renews its turn, anyone
// else waits. A liveness death that read the generation a provider callback
// had just taken used to take the recovery back from it.
func TestRecoveryTakeIfRefusesStrangersWhileAnOwnerRuns(t *testing.T) {
	var r recovery
	run, gen := r.take(context.Background())

	if _, _, ok := r.takeIf(context.Background(), gen, false); ok {
		t.Fatal("takeIf let a stranger take the recovery from a running owner")
	}
	select {
	case <-run.Done():
		t.Fatal("the refused takeIf ended the owner's run anyway")
	default:
	}

	renewed, next, ok := r.takeIf(context.Background(), gen, true)
	if !ok {
		t.Fatal("takeIf refused the owner its own turn")
	}
	if next == gen {
		t.Fatalf("takeIf renewed at generation %d, want a new one", next)
	}
	r.release(next)
	select {
	case <-renewed.Done():
	default:
		t.Fatal("release left the renewed run open")
	}
}

func TestRecoveryTakeIfRefusesAnOldGeneration(t *testing.T) {
	var r recovery
	_, old := r.take(context.Background())
	_, gen := r.take(context.Background())
	if _, _, ok := r.takeIf(context.Background(), old, true); ok {
		t.Fatal("takeIf accepted a generation that had been taken over")
	}
	r.release(gen)
	if _, _, ok := r.takeIf(context.Background(), gen, false); !ok {
		t.Fatal("takeIf refused a caller although no owner was running")
	}
}
