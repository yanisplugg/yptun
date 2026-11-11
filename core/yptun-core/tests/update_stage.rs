use yptun_core::update_stage::{UpdateStage, UpdateStageKind};

#[test]
fn stage_holds_its_fields() {
    let s = UpdateStage::new(UpdateStageKind::ApplyPatch, 2, 4);
    assert_eq!(s.kind, UpdateStageKind::ApplyPatch);
    assert_eq!(s.step, 2);
    assert_eq!(s.total, 4);
    assert_eq!(s, UpdateStage { kind: UpdateStageKind::ApplyPatch, step: 2, total: 4 });
    assert_ne!(s, UpdateStage::new(UpdateStageKind::Install, 2, 4));
}
