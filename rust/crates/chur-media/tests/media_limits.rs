//! The video bounds apply to the long and short edge after rotation.
use chur_format::constants::MediaClass;
use chur_media::import::CanonicalMedia;

#[test]
fn video_bounds_allow_both_orientations() {
    for (width, height) in [(7680, 4320), (4320, 7680)] {
        assert!(
            CanonicalMedia {
                media_class: MediaClass::Video,
                width,
                height,
                duration_ms: 1000
            }
            .check()
            .is_ok()
        );
    }
}

#[test]
fn video_bounds_reject_an_excess_on_either_edge() {
    for (width, height) in [(7681, 4320), (4320, 7681), (7680, 4321), (4321, 7680)] {
        assert!(
            CanonicalMedia {
                media_class: MediaClass::Video,
                width,
                height,
                duration_ms: 1000
            }
            .check()
            .is_err()
        );
    }
}
