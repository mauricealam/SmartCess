"""Rate limiter tests (fake clock, no sleeps)."""

from app.ratelimit import RateLimiter


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def test_burst_up_to_limit():
    clock = Clock()
    limiter = RateLimiter(clock=clock)
    assert [limiter.allow("u", 3, 60.0) for _ in range(4)] == [True, True, True, False]


def test_window_expiry_refills():
    clock = Clock()
    limiter = RateLimiter(clock=clock)
    assert limiter.allow("u", 1, 10.0)
    assert not limiter.allow("u", 1, 10.0)
    clock.now += 10.0
    assert limiter.allow("u", 1, 10.0)


def test_keys_are_independent():
    clock = Clock()
    limiter = RateLimiter(clock=clock)
    assert limiter.allow("a", 1, 60.0)
    assert not limiter.allow("a", 1, 60.0)
    assert limiter.allow("b", 1, 60.0)


def test_reset_clears():
    clock = Clock()
    limiter = RateLimiter(clock=clock)
    assert limiter.allow("u", 1, 60.0)
    limiter.reset()
    assert limiter.allow("u", 1, 60.0)
