use std::{
    future::{Future, IntoFuture},
    pin::Pin,
    task::{Context, Poll},
};

pub struct BoxFuture<'a, T>(Pin<Box<dyn Future<Output = T> + 'a>>);

impl<'a, T> BoxFuture<'a, T> {
    pub fn new<F>(f: F) -> BoxFuture<'a, T>
    where
        F: IntoFuture<Output = T> + 'a,
    {
        BoxFuture(Box::pin(f.into_future()))
    }

    #[allow(unused)]
    pub fn wrap(f: Pin<Box<dyn Future<Output = T> + 'a>>) -> BoxFuture<'a, T> {
        BoxFuture(f)
    }
}

unsafe impl<T: Send> Send for BoxFuture<'_, T> {}

impl<T> Future for BoxFuture<'_, T> {
    type Output = T;

    fn poll(mut self: Pin<&mut Self>, context: &mut Context<'_>) -> Poll<Self::Output> {
        self.0.as_mut().poll(context)
    }
}

pub type Runner = BoxFuture<'static, std::io::Result<()>>;
