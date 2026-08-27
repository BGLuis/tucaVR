#[derive(Debug, Default)]
pub struct PrerollState {
    awaiting_landing: bool,
    catching_up: bool,
}

impl PrerollState {
    pub fn idle() -> Self {
        Self { awaiting_landing: false, catching_up: false }
    }

    pub fn begin(&mut self) {
        self.awaiting_landing = true;
        self.catching_up = true;
    }

    pub fn is_awaiting_landing(&self) -> bool { self.awaiting_landing }

    pub fn is_active(&self) -> bool {
        self.catching_up
    }

    pub fn should_skip_packet(&mut self, is_key: bool, lag: f64, threshold: f64) -> bool {
        if self.catching_up {
            if lag <= threshold {
                self.catching_up = false;
            } else {
                return false;
            }
        }
        lag > threshold && !is_key
    }

    pub fn take_landing(&mut self) -> bool {
        if self.awaiting_landing {
            self.awaiting_landing = false;
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inactive_by_default_never_claims_a_landing_frame() {
        let mut preroll = PrerollState::idle();
        assert!(!preroll.is_active());
        assert!(!preroll.take_landing());
        assert!(!preroll.take_landing());
    }

    #[test]
    fn first_output_frame_after_begin_is_the_landing_frame() {
        let mut preroll = PrerollState::idle();
        preroll.begin();

        assert!(preroll.take_landing());
    }

    #[test]
    fn only_the_first_output_frame_is_claimed() {
        let mut preroll = PrerollState::idle();
        preroll.begin();
        preroll.take_landing();

        assert!(!preroll.take_landing());
        assert!(!preroll.take_landing());
    }

    #[test]
    fn does_not_resume_skipping_immediately_after_the_landing_frame() {
        let mut preroll = PrerollState::idle();
        preroll.begin();
        preroll.take_landing();

        assert!(preroll.is_active());
        assert!(!preroll.should_skip_packet(false, 3.0, 0.5));
        assert!(!preroll.should_skip_packet(false, 2.5, 0.5));
    }

    #[test]
    fn should_skip_packet_stays_disabled_while_catching_up() {
        let mut preroll = PrerollState::idle();
        preroll.begin();

        assert!(!preroll.should_skip_packet(false, 5.0, 0.5));
        assert!(!preroll.should_skip_packet(true, 5.0, 0.5));
        assert!(preroll.is_active());
    }

    #[test]
    fn should_skip_packet_ends_catch_up_once_lag_settles_and_resumes_normal_behavior() {
        let mut preroll = PrerollState::idle();
        preroll.begin();
        preroll.take_landing();

        assert!(!preroll.should_skip_packet(false, 1.0, 0.5));
        assert!(!preroll.should_skip_packet(false, 0.5, 0.5));
        assert!(!preroll.is_active());

        assert!(preroll.should_skip_packet(false, 0.6, 0.5));
        assert!(!preroll.should_skip_packet(true, 0.6, 0.5));
    }

    #[test]
    fn should_skip_packet_matches_prior_catch_up_behavior_outside_preroll() {
        let mut preroll = PrerollState::idle();

        assert!(preroll.should_skip_packet(false, 0.6, 0.5));
        assert!(!preroll.should_skip_packet(true, 0.6, 0.5));
        assert!(!preroll.should_skip_packet(false, 0.4, 0.5));
    }
}
