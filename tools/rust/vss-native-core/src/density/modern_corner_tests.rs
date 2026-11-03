use super::*;
use serde_json::json;

#[test]
fn dense_corner_buffers_obey_the_shared_budget_and_reuse_capacity() {
    let volume = ModernVolume {
        size: [16; 3],
        origin: [0; 3],
        step: [1; 3],
    };
    let mut buffers = vec![DenseCorners::default(); 64];
    let mut total = 0;
    let mut accepted = 0;
    for buffer in &mut buffers {
        if buffer.prepare(volume, DENSE_CORNER_POINTS, &mut total) {
            accepted += 1;
        }
        assert!(total <= DENSE_CORNER_BYTES);
        assert_eq!(total, buffers_bytes_so_far(accepted));
    }
    assert_eq!(
        accepted,
        DENSE_CORNER_BYTES / (DENSE_CORNER_POINTS * std::mem::size_of::<Option<f32>>())
    );
    let allocated = total;
    let pointer = buffers[0].values.as_ptr();
    buffers[0].values[0] = Some(0.75);
    buffers[0].invalidate();
    let small = ModernVolume::blocks([2; 3], [-1; 3]);
    assert!(buffers[0].prepare(small, 8, &mut total));
    assert_eq!(buffers[0].values.as_ptr(), pointer);
    assert_eq!(total, allocated);
    assert!(buffers[0].values.iter().all(Option::is_none));
    // Growing this buffer while the other buffers retain their allocations
    // must reject the request without changing its existing identity or values.
    buffers[0].values[0] = Some(0.25);
    assert!(!buffers[0].prepare(ModernVolume::blocks([17; 3], [0; 3]), 4913, &mut total));
    assert_eq!(buffers[0].volume, Some(small));
    assert_eq!(buffers[0].values[0], Some(0.25));
    assert_eq!(total, allocated);
}
fn buffers_bytes_so_far(count: usize) -> usize {
    count * DENSE_CORNER_POINTS * std::mem::size_of::<Option<f32>>()
}

#[test]
fn dense_index_rejects_large_misaligned_and_invalid_volumes() {
    let volume = ModernVolume {
        origin: [-16, -8, 4],
        size: [3, 5, 4],
        step: [4, 8, 4],
    };
    assert_eq!(dense_corner_index([-8, 24, 16], volume), Some((59, 60)));
    for q in [[-17, -8, 4], [-15, -8, 4], [-4, -8, 4], [-16, 32, 4]] {
        assert_eq!(dense_corner_index(q, volume), None);
    }
    for size in [[0, 2, 2], [-1, 2, 2], [i32::MAX; 3], [17; 3]] {
        assert_eq!(
            dense_corner_index(
                [0; 3],
                ModernVolume {
                    size,
                    origin: [0; 3],
                    step: [1; 3]
                }
            ),
            None
        );
    }
}

#[test]
fn dense_corners_match_hash_fallback_across_volumes_beards_and_resets() {
    let interp = |input| json!({"type":"minecraft:interpolated", "cell_size_xz":4, "cell_size_y":8, "input":input});
    let doc = json!({"vss_density_semantics":"float-26.3","settings":{"legacy_random_source":false,"noise_router":{
        "final_density":interp(json!({"type":"minecraft:add", "left":{"type":"minecraft:beardifier"},
            "right":interp(json!({"type":"minecraft:gradient","axis":"y","from_coordinate":-64,"to_coordinate":320,"from_value":1.,"to_value":-1.}))}))
    }}});
    let g = Graph::from_document(0, &doc).unwrap();
    let id = g.root("final_density").unwrap();
    let mut dense = g.scratch(-16, 0, 4, 8).unwrap();
    let mut hash = g.scratch(-16, 0, 4, 8).unwrap();
    // Reserve the budget to exercise the unchanged wide hash path.
    hash.modern_dense_bytes = DENSE_CORNER_BYTES;
    for reset in [false, true] {
        if reset {
            let bytes = dense.modern_dense_bytes;
            dense.reset_chunk(32, -32);
            hash.reset_chunk(32, -32);
            assert_eq!(dense.modern_dense_bytes, bytes);
            assert!(dense.modern_dense.iter().all(|v| v.volume.is_none()));
        }
        for beard in [0., 0.75, -0.5, 0.] {
            dense.set_beard(beard);
            hash.set_beard(beard);
            for volume in [
                ModernVolume::blocks([16, 32, 16], [-16, -8, 0]),
                ModernVolume::blocks([1, 16, 1], [1, -8, 1]),
                ModernVolume::blocks([256; 3], [-128; 3]),
            ] {
                dense.set_modern_volume(volume);
                hash.set_modern_volume(volume);
                for mode in [
                    Mode::Block,
                    Mode::Single,
                    Mode::Slice,
                    Mode::Cell,
                    Mode::Raw,
                ] {
                    for p in [[1, -3, 1], [4, 9, 4], [-12, -7, 0], [1, -3, 1]] {
                        let expected = g.compute(id, p, mode, &mut hash);
                        let actual = g.compute(id, p, mode, &mut dense);
                        assert_eq!(
                            actual.to_bits(),
                            expected.to_bits(),
                            "{p:?} {mode:?} {volume:?} beard={beard}"
                        );
                    }
                }
            }
        }
    }
    assert!(dense.modern_dense_bytes > 0);
    assert!(hash.modern_dense.iter().all(|v| v.values.is_empty()));
    assert!(
        !dense.modern_corners.is_empty(),
        "oversized volumes must use the hash fallback"
    );
    let payload: usize = dense
        .modern_dense
        .iter()
        .map(|v| v.values.capacity() * std::mem::size_of::<Option<f32>>())
        .sum();
    assert_eq!(dense.modern_dense_bytes, payload);
    let charged = dense.retained_bytes();
    dense.modern_dense_bytes = 0;
    assert_eq!(charged - dense.retained_bytes(), payload);
}
